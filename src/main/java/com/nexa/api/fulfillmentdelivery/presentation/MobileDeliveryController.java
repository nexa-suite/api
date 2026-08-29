package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.port.MobileDeliveryContractPort;
import com.nexa.api.fulfillmentdelivery.application.service.MobileDeliveryContractService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
    @Operation(operationId = "issueDeliveryBuyerHandoffToken")
    public ResponseEntity<IssuedHandoffResponse> issue(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @Parameter(name = "Idempotency-Key", required = true, description = "Stable key for retry-safe handoff issuance")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IssueHandoffRequest request) {
        MobileDeliveryContractService.IssuedHandoff result = service.issue(context, deliveryId, request.attemptId(), idempotencyKey);
        return ResponseEntity.status(result.token() == null ? 200 : 201).body(new IssuedHandoffResponse(
                result.handoffId(), result.deliveryId(), result.attemptId(), result.expiresAt(), result.status(), result.token()));
    }

    @PostMapping("/delivery-handoff/validations")
    @Operation(operationId = "validateDeliveryBuyerHandoffToken")
    public MobileDeliveryContractPort.HandoffValidation validate(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @Valid @RequestBody HandoffValidationRequest request) {
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

    public record IssueHandoffRequest(@NotNull UUID attemptId) { }
    public record HandoffValidationRequest(@NotBlank @Size(max = 400) String token) { }
    public record BuyerReceiptRequest(@NotBlank @Size(max = 400) String token,
                                      @NotBlank @Size(max = 16) String decision,
                                      @NotNull @PositiveOrZero BigDecimal acceptedQuantity,
                                      @Size(max = 2000) String reason) { }
    public record IssuedHandoffResponse(UUID handoffId, UUID deliveryId, UUID attemptId,
                                        Instant expiresAt, String status, String token) { }
}
