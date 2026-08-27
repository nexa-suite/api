package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentLifecycleService;
import com.nexa.api.fulfillmentdelivery.domain.model.delivery.DeliveryAttemptOutcome;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Canonical BC-06 command surface. Legacy dispatch endpoints remain in
 * {@link LogisticsController}; these resources expose the v0.15 fulfillment
 * and delivery model without changing the v0.14 contract.
 */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class FulfillmentController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final FulfillmentLifecycleService service;

    public FulfillmentController(FulfillmentLifecycleService service) {
        this.service = service;
    }

    @GetMapping("/fulfillments/{fulfillmentId}")
    @Operation(operationId = "getFulfillment")
    public ResponseEntity<FulfillmentModels.FulfillmentView> getFulfillment(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId) {
        FulfillmentModels.FulfillmentView value = service.get(context, fulfillmentId);
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PostMapping("/sales-orders/{salesOrderId}/fulfillments")
    @Operation(operationId = "startFulfillment")
    public ResponseEntity<FulfillmentModels.FulfillmentView> startFulfillment(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID salesOrderId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        FulfillmentModels.FulfillmentView value = service.start(context, salesOrderId, version(ifMatch), idempotencyKey);
        return ResponseEntity.status(201).eTag(etag(value.version())).body(value);
    }

    @PostMapping("/fulfillments/{fulfillmentId}/picking-starts")
    @Operation(operationId = "startFulfillmentPicking")
    public ResponseEntity<FulfillmentModels.FulfillmentView> startPicking(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return fulfillmentMutation(service.startPicking(context, fulfillmentId, version(ifMatch), idempotencyKey));
    }

    @PostMapping("/fulfillments/{fulfillmentId}/picking-confirmations")
    @Operation(operationId = "confirmFulfillmentPicking")
    public ResponseEntity<FulfillmentModels.FulfillmentView> confirmPicking(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PickingRequest request) {
        FulfillmentLifecycleService.PickingCommand command = new FulfillmentLifecycleService.PickingCommand(
                request.pickerIdentityId(), request.startedAt(), request.completedAt(), request.notes(),
                request.lines().stream().map(line -> new FulfillmentLifecycleService.PickingLine(
                        line.fulfillmentLineId(), line.skuId(), line.quantity(), line.unit())).toList());
        return fulfillmentMutation(service.confirmPicking(context, fulfillmentId, version(ifMatch), idempotencyKey, command));
    }

    @PostMapping("/fulfillments/{fulfillmentId}/shortage-resolutions")
    @Operation(operationId = "resolveFulfillmentShortage")
    public ResponseEntity<FulfillmentModels.FulfillmentView> resolveShortage(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ShortageResolutionRequest request) {
        FulfillmentLifecycleService.ShortageResolutionCommand command = new FulfillmentLifecycleService.ShortageResolutionCommand(
                request.reason(), request.lines().stream().map(line -> new FulfillmentLifecycleService.ShortageLineCommand(
                        line.fulfillmentLineId(), line.skuId(), line.quantity(), line.unit())).toList());
        return fulfillmentMutation(service.resolveShortage(context, fulfillmentId, version(ifMatch), idempotencyKey, command));
    }

    @PostMapping("/fulfillments/{fulfillmentId}/packing")
    @Operation(operationId = "packFulfillment")
    public ResponseEntity<FulfillmentModels.FulfillmentView> pack(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return fulfillmentMutation(service.pack(context, fulfillmentId, version(ifMatch), idempotencyKey));
    }

    @PostMapping("/fulfillments/{fulfillmentId}/staging")
    @Operation(operationId = "stageFulfillment")
    public ResponseEntity<FulfillmentModels.FulfillmentView> stage(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return fulfillmentMutation(service.stage(context, fulfillmentId, version(ifMatch), idempotencyKey));
    }

    @PostMapping("/fulfillments/{fulfillmentId}/ready-for-dispatch")
    @Operation(operationId = "markFulfillmentReadyForDispatch")
    public ResponseEntity<FulfillmentModels.FulfillmentView> readyForDispatch(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return fulfillmentMutation(service.readyForDispatch(context, fulfillmentId, version(ifMatch), idempotencyKey));
    }

    @PostMapping("/fulfillments/{fulfillmentId}/dispatches")
    @Operation(operationId = "dispatchFulfillment")
    public ResponseEntity<FulfillmentModels.FulfillmentView> dispatch(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return fulfillmentMutation(service.dispatch(context, fulfillmentId, version(ifMatch), idempotencyKey));
    }

    @GetMapping("/deliveries/{deliveryId}")
    @Operation(operationId = "getDelivery")
    public ResponseEntity<FulfillmentModels.DeliveryView> getDelivery(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId) {
        FulfillmentModels.DeliveryView value = service.getDelivery(context, deliveryId);
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PostMapping("/deliveries/{deliveryId}/transit-starts")
    @Operation(operationId = "startDeliveryTransit")
    public ResponseEntity<FulfillmentModels.DeliveryView> startTransit(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return deliveryMutation(service.startDelivery(context, deliveryId, version(ifMatch), idempotencyKey));
    }

    @PostMapping("/deliveries/{deliveryId}/attempts")
    @Operation(operationId = "recordDeliveryAttempt")
    public ResponseEntity<FulfillmentModels.DeliveryOutcomeResult> recordAttempt(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AttemptRequest request) {
        FulfillmentLifecycleService.AttemptCommand command = new FulfillmentLifecycleService.AttemptCommand(
                outcome(request.outcome()), request.failureReason(), request.notes(), request.attemptedAt(),
                request.lines() == null ? List.of() : request.lines().stream().map(line ->
                        new FulfillmentLifecycleService.AttemptLineCommand(line.fulfillmentLineId(), line.skuId(),
                                line.attemptedQuantity(), line.deliveredQuantity(), line.rejectedQuantity(),
                                line.cancelledQuantity(), line.unit())).toList());
        FulfillmentModels.DeliveryOutcomeResult value = service.recordAttempt(
                context, deliveryId, version(ifMatch), idempotencyKey, command);
        return ResponseEntity.ok().eTag(etag(value.delivery().version())).body(value);
    }

    @PostMapping("/deliveries/{deliveryId}/pod")
    @Operation(operationId = "captureDeliveryProofOfDelivery")
    public ResponseEntity<FulfillmentModels.PodView> capturePod(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PodRequest request) {
        FulfillmentModels.PodView value = service.capturePod(context, deliveryId, version(ifMatch), idempotencyKey,
                new FulfillmentLifecycleService.PodCommand(request.receiverName(), request.capturedAt(), request.notes(),
                        request.photoEvidenceObjectId(), request.signatureEvidenceObjectId()));
        return ResponseEntity.status(201).eTag(etag(value.deliveryVersion())).body(value);
    }

    @PostMapping("/deliveries/{deliveryId}/pod/seals")
    @Operation(operationId = "sealDeliveryProofOfDelivery")
    public ResponseEntity<FulfillmentModels.PodView> sealPod(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PodSealRequest request) {
        Instant sealedAt = request == null ? null : request.sealedAt();
        FulfillmentModels.PodView value = service.sealPod(context, deliveryId, version(ifMatch), idempotencyKey, sealedAt);
        return ResponseEntity.ok().eTag(etag(value.deliveryVersion())).body(value);
    }

    @PostMapping("/deliveries/{deliveryId}/temperature-evidence")
    @Operation(operationId = "recordDeliveryTemperatureEvidence")
    public ResponseEntity<FulfillmentModels.TemperatureView> recordTemperature(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID deliveryId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TemperatureRequest request) {
        FulfillmentModels.TemperatureView value = service.recordTemperature(context, deliveryId, version(ifMatch), idempotencyKey,
                new FulfillmentLifecycleService.TemperatureCommand(request.lotId(), request.temperatureCelsius(),
                        request.unit(), request.source(), request.evidenceMetadata(), request.recordedAt()));
        return ResponseEntity.status(201).eTag(etag(value.deliveryVersion())).body(value);
    }

    private static ResponseEntity<FulfillmentModels.FulfillmentView> fulfillmentMutation(FulfillmentModels.FulfillmentView value) {
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    private static ResponseEntity<FulfillmentModels.DeliveryView> deliveryMutation(FulfillmentModels.DeliveryView value) {
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw new FulfillmentOperationException("PRECONDITION_REQUIRED", false);
        String candidate = value.trim();
        if (candidate.regionMatches(true, 0, "W/", 0, 2)) candidate = candidate.substring(2).trim();
        candidate = candidate.replace("\"", "");
        try {
            long parsed = Long.parseLong(candidate);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new FulfillmentOperationException("PRECONDITION_REQUIRED", false);
        }
    }

    private static DeliveryAttemptOutcome outcome(String value) {
        if (value == null || value.isBlank()) throw new FulfillmentOperationException("DELIVERY_OUTCOME_REQUIRED", false);
        try { return DeliveryAttemptOutcome.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            throw new FulfillmentOperationException("DELIVERY_OUTCOME_REQUIRED", false);
        }
    }

    public record PickingRequest(UUID pickerIdentityId, Instant startedAt, Instant completedAt,
                                 @Size(max = 2000) String notes,
                                 @NotNull @Size(min = 1, max = 500) List<@Valid PickingLineRequest> lines) { }

    public record PickingLineRequest(@NotNull UUID fulfillmentLineId, @NotNull UUID skuId,
                                     @NotNull @PositiveOrZero BigDecimal quantity,
                                     @NotBlank @Size(max = 32) String unit) { }

    public record ShortageResolutionRequest(@NotBlank @Size(max = 2000) String reason,
                                            @NotNull @Size(min = 1, max = 500)
                                            List<@Valid ShortageResolutionLineRequest> lines) { }

    public record ShortageResolutionLineRequest(@NotNull UUID fulfillmentLineId, @NotNull UUID skuId,
                                                @NotNull @Positive BigDecimal quantity,
                                                @NotBlank @Size(max = 32) String unit) { }

    public record AttemptRequest(@NotBlank @Size(max = 32) String outcome,
                                 @Size(max = 2000) String failureReason,
                                 @Size(max = 2000) String notes, Instant attemptedAt,
                                 @Size(max = 500) List<@Valid AttemptLineRequest> lines) { }

    public record AttemptLineRequest(@NotNull UUID fulfillmentLineId, @NotNull UUID skuId,
                                     @NotNull @Positive BigDecimal attemptedQuantity,
                                     @NotNull @PositiveOrZero BigDecimal deliveredQuantity,
                                     @NotNull @PositiveOrZero BigDecimal rejectedQuantity,
                                     @NotNull @PositiveOrZero BigDecimal cancelledQuantity,
                                     @NotBlank @Size(max = 32) String unit) { }

    public record PodRequest(@NotBlank @Size(max = 255) String receiverName,
                             Instant capturedAt, @Size(max = 2000) String notes,
                             UUID photoEvidenceObjectId, UUID signatureEvidenceObjectId) { }

    public record PodSealRequest(Instant sealedAt) { }

    public record TemperatureRequest(UUID lotId, @NotNull BigDecimal temperatureCelsius,
                                     @Size(max = 16) String unit, @Size(max = 64) String source,
                                     @Size(max = 2000) String evidenceMetadata, Instant recordedAt) { }
}
