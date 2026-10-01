package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.AttemptStartResult;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.DeliveryView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentLifecycleService;
import com.nexa.api.fulfillmentdelivery.domain.model.delivery.DeliveryAttemptOutcome;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Protected BC-06 driver routes; every read is scoped to the current assignment. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/driver/deliveries")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class DriverDeliveryController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final DriverDeliveryService service;

    public DriverDeliveryController(DriverDeliveryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listCurrentDriverDeliveries")
    public List<DeliveryView> list(@RequestAttribute(ACCESS) CurrentAccessContext context) {
        return service.listAssigned(context);
    }

    @GetMapping("/{deliveryId}")
    @Operation(operationId = "getCurrentDriverDelivery")
    public ResponseEntity<DeliveryView> get(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                            @PathVariable UUID deliveryId) {
        DeliveryView value = service.getAssigned(context, deliveryId);
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PostMapping("/{deliveryId}/attempts")
    @Operation(operationId = "startCurrentDriverDeliveryAttempt")
    public ResponseEntity<AttemptStartResult> start(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                     @PathVariable UUID deliveryId,
                                                     @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                     @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        AttemptStartResult value = service.startAttempt(context, deliveryId, version(ifMatch), key);
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.delivery().version())).body(value);
    }

    @PostMapping("/{deliveryId}/attempts/{attemptId}/outcomes")
    @Operation(operationId = "recordCurrentDriverAttemptOutcome")
    public ResponseEntity<FulfillmentModels.DeliveryOutcomeResult> recordOutcome(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @PathVariable UUID attemptId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AttemptOutcomeRequest request) {
        FulfillmentLifecycleService.AttemptCommand command = new FulfillmentLifecycleService.AttemptCommand(
                outcome(request.outcome()), request.failureReason(), request.notes(), request.attemptedAt(),
                request.lines() == null ? List.of() : request.lines().stream().map(line ->
                        new FulfillmentLifecycleService.AttemptLineCommand(line.fulfillmentLineId(), line.skuId(),
                                line.attemptedQuantity(), line.deliveredQuantity(), line.rejectedQuantity(),
                                line.cancelledQuantity(), line.unit())).toList());
        FulfillmentModels.DeliveryOutcomeResult value = service.recordOutcome(
                context, deliveryId, attemptId, version(ifMatch), key, command);
        return ResponseEntity.ok().eTag(etag(value.delivery().version())).body(value);
    }

    private static DeliveryAttemptOutcome outcome(String value) {
        try {
            return DeliveryAttemptOutcome.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(
                    "DELIVERY_OUTCOME_INVALID", false);
        }
    }

    public record AttemptOutcomeRequest(@NotBlank @Size(max = 32) String outcome,
                                        @Size(max = 2000) String failureReason,
                                        @Size(max = 2000) String notes, Instant attemptedAt,
                                        @Size(max = 500) List<@Valid AttemptLineRequest> lines) { }

    public record AttemptLineRequest(@NotNull UUID fulfillmentLineId, @NotNull UUID skuId,
                                     @NotNull @Positive BigDecimal attemptedQuantity,
                                     @NotNull @PositiveOrZero BigDecimal deliveredQuantity,
                                     @NotNull @PositiveOrZero BigDecimal rejectedQuantity,
                                     @NotNull @PositiveOrZero BigDecimal cancelledQuantity,
                                     @NotBlank @Size(max = 32) String unit) { }

    private static String etag(long version) { return "\"" + version + "\""; }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException("PRECONDITION_REQUIRED", false);
        String candidate = value.trim();
        if (candidate.regionMatches(true, 0, "W/", 0, 2)) candidate = candidate.substring(2).trim();
        try {
            long parsed = Long.parseLong(candidate.replace("\"", ""));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException("PRECONDITION_INVALID", false);
        }
    }
}
