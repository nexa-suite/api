package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.port.MobileDeliveryContractPort;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.service.MobileDeliveryContractService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Additive BC-06 Mobile V1 handoff and buyer receipt contracts. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class MobileDeliveryController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final MobileDeliveryContractService service;

    public MobileDeliveryController(MobileDeliveryContractService service) {
        this.service = service;
    }

    @PostMapping("/deliveries/{deliveryId}/handoff-tokens")
    @Operation(operationId = "issueDeliveryBuyerHandoffToken", responses = {
            @ApiResponse(responseCode = "201", description = "Handoff identity issued",
                    content = @Content(schema = @Schema(oneOf = {IssuedHandoffResponse.class,
                            IssuedDispatchHandoffResponse.class}, properties = {
                            @StringToClassMapItem(key = "purpose", value = String.class),
                            @StringToClassMapItem(key = "handoffId", value = UUID.class),
                            @StringToClassMapItem(key = "deliveryId", value = UUID.class),
                            @StringToClassMapItem(key = "attemptId", value = UUID.class),
                            @StringToClassMapItem(key = "assignmentId", value = UUID.class),
                            @StringToClassMapItem(key = "deliveryVersion", value = Long.class),
                            @StringToClassMapItem(key = "expiresAt", value = Instant.class),
                            @StringToClassMapItem(key = "status", value = String.class),
                            @StringToClassMapItem(key = "token", value = String.class)}))),
            @ApiResponse(responseCode = "200", description = "Idempotent replay; token is omitted",
                    content = @Content(schema = @Schema(oneOf = {IssuedHandoffResponse.class,
                            IssuedDispatchHandoffResponse.class}, properties = {
                            @StringToClassMapItem(key = "purpose", value = String.class),
                            @StringToClassMapItem(key = "handoffId", value = UUID.class),
                            @StringToClassMapItem(key = "deliveryId", value = UUID.class),
                            @StringToClassMapItem(key = "attemptId", value = UUID.class),
                            @StringToClassMapItem(key = "assignmentId", value = UUID.class),
                            @StringToClassMapItem(key = "deliveryVersion", value = Long.class),
                            @StringToClassMapItem(key = "expiresAt", value = Instant.class),
                            @StringToClassMapItem(key = "status", value = String.class),
                            @StringToClassMapItem(key = "token", value = String.class)})))
    })
    public ResponseEntity<?> issue(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @Parameter(name = "Idempotency-Key", required = true, description = "Stable key for retry-safe handoff issuance")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IssueHandoffRequest request) {
        if ("DISPATCH_HANDOFF".equals(request.purpose())) {
            if (request.attemptId() != null || request.assignmentId() == null) throw invalidHandoffRequest();
            MobileDeliveryContractService.IssuedDispatchHandoff result = service.issueDispatchHandoff(
                    context, deliveryId, request.assignmentId(), idempotencyKey);
            return ResponseEntity.status(result.token() == null ? 200 : 201)
                    .body(new IssuedDispatchHandoffResponse(result.purpose(), result.handoffId(), result.deliveryId(),
                            result.assignmentId(), result.deliveryVersion(), result.expiresAt(), result.status(), result.token()));
        }
        if (request.purpose() != null || request.assignmentId() != null) throw invalidHandoffRequest();
        MobileDeliveryContractService.IssuedHandoff result = service.issue(context, deliveryId, request.attemptId(), idempotencyKey);
        return ResponseEntity.status(result.token() == null ? 200 : 201).body(new IssuedHandoffResponse(
                result.handoffId(), result.deliveryId(), result.attemptId(), result.expiresAt(), result.status(), result.token()));
    }

    @PostMapping("/delivery-handoff/validations")
    @Operation(operationId = "validateDeliveryBuyerHandoffToken", responses = {
            @ApiResponse(responseCode = "200", description = "Validated handoff identity",
                    content = @Content(schema = @Schema(oneOf = {MobileDeliveryContractPort.HandoffValidation.class,
                            DispatchHandoffValidationResponse.class}, properties = {
                            @StringToClassMapItem(key = "purpose", value = String.class),
                            @StringToClassMapItem(key = "handoffId", value = UUID.class),
                            @StringToClassMapItem(key = "deliveryId", value = UUID.class),
                            @StringToClassMapItem(key = "attemptId", value = UUID.class),
                            @StringToClassMapItem(key = "assignmentId", value = UUID.class),
                            @StringToClassMapItem(key = "deliveryVersion", value = Long.class),
                            @StringToClassMapItem(key = "expiresAt", value = Instant.class),
                            @StringToClassMapItem(key = "deliveryStatus", value = String.class),
                            @StringToClassMapItem(key = "deliveredQuantity", value = BigDecimal.class),
                            @StringToClassMapItem(key = "status", value = String.class)})))
    })
    public Object validate(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @Valid @RequestBody HandoffValidationRequest request) {
        if ("DISPATCH_HANDOFF".equals(request.purpose())) {
            MobileDeliveryContractPort.DispatchHandoffValidation result = service.validateDispatchHandoff(
                    context, request.deliveryId(), request.assignmentId(), request.token());
            return new DispatchHandoffValidationResponse("DISPATCH_HANDOFF", result.handoffId(), result.deliveryId(),
                    result.assignmentId(), result.deliveryVersion(), result.expiresAt(), result.status());
        }
        if (request.purpose() != null || request.deliveryId() != null || request.assignmentId() != null) {
            throw invalidHandoffRequest();
        }
        return service.validate(context, request.token());
    }

    @PostMapping("/deliveries/{deliveryId}/buyer-receipts")
    @Operation(operationId = "recordBuyerDeliveryReceipt")
    public ResponseEntity<MobileDeliveryContractPort.BuyerReceipt> receipt(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @Parameter(name = "Idempotency-Key", required = true, description = "Stable key for retry-safe buyer receipt")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BuyerReceiptRequest request) {
        MobileDeliveryContractPort.BuyerReceipt result = service.recordReceipt(context, deliveryId, request.token(),
                request.decision(), request.acceptedQuantity(), request.reason(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
    }

    private static FulfillmentOperationException invalidHandoffRequest() {
        return new FulfillmentOperationException("DELIVERY_HANDOFF_REQUEST_INVALID", false);
    }

    public record IssueHandoffRequest(UUID attemptId, @Size(max = 32) String purpose, UUID assignmentId) { }
    public record HandoffValidationRequest(@NotBlank @Size(max = 400) String token,
                                           @Size(max = 32) String purpose,
                                           UUID deliveryId, UUID assignmentId) { }
    public record BuyerReceiptRequest(@NotBlank @Size(max = 400) String token,
                                      @NotBlank @Size(max = 16) String decision,
                                      @NotNull @PositiveOrZero BigDecimal acceptedQuantity,
                                      @Size(max = 2000) String reason) { }
    public record IssuedHandoffResponse(UUID handoffId, UUID deliveryId, UUID attemptId,
                                        Instant expiresAt, String status, String token) { }
    @Schema(name = "IssuedDispatchHandoffResponse")
    public record IssuedDispatchHandoffResponse(String purpose, UUID handoffId, UUID deliveryId,
                                                UUID assignmentId, long deliveryVersion, Instant expiresAt,
                                                String status, String token) { }
    @Schema(name = "DispatchHandoffValidationResponse")
    public record DispatchHandoffValidationResponse(String purpose, UUID handoffId, UUID deliveryId,
                                                    UUID assignmentId, long deliveryVersion, Instant expiresAt,
                                                    String status) { }
}
