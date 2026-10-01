package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort.FulfillmentDriverAssignmentView;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentDriverAssignmentService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Current driver responsibility fact for a prepared Fulfillment. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/fulfillments/{fulfillmentId}/driver-assignments")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class FulfillmentDriverAssignmentController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";

    private final FulfillmentDriverAssignmentService service;

    public FulfillmentDriverAssignmentController(FulfillmentDriverAssignmentService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "getPreparedFulfillmentDriverAssignment")
    public ResponseEntity<AssignmentResponse> current(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId) {
        Optional<FulfillmentDriverAssignmentView> current = service.current(context, fulfillmentId);
        return current.map(value -> ResponseEntity.ok().eTag(etag(value.fulfillmentVersion()))
                        .body(response(value)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    @Operation(operationId = "assignDriverToPreparedFulfillment")
    public ResponseEntity<AssignmentResponse> assign(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AssignmentRequest request) {
        FulfillmentDriverAssignmentView value = service.assign(context, fulfillmentId, version(ifMatch),
                request.physicalAllocationId(), request.physicalAllocationVersion(),
                request.responsibleMembershipId(), idempotencyKey);
        return ResponseEntity.ok().eTag(etag(value.fulfillmentVersion())).body(response(value));
    }

    @PostMapping("/plan-changes")
    @Operation(operationId = "changePreparedFulfillmentDispatchPlan")
    public ResponseEntity<AssignmentResponse> changePlan(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlanChangeRequest request) {
        FulfillmentDriverAssignmentView value = service.changePlan(context, fulfillmentId, version(ifMatch),
                request.expectedAssignmentId(), request.expectedAssignmentVersion(),
                request.physicalAllocationId(), request.physicalAllocationVersion(),
                request.responsibleMembershipId(), request.plannedDispatchAt(), idempotencyKey);
        return ResponseEntity.ok().eTag(etag(value.fulfillmentVersion())).body(response(value));
    }

    @GetMapping("/history")
    @Operation(operationId = "getPreparedFulfillmentDriverAssignmentHistory")
    public ResponseEntity<List<AssignmentResponse>> history(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID fulfillmentId) {
        return ResponseEntity.ok(service.history(context, fulfillmentId).stream()
                .map(FulfillmentDriverAssignmentController::response).toList());
    }

    private static AssignmentResponse response(FulfillmentDriverAssignmentView value) {
        return new AssignmentResponse(value.id(), value.fulfillmentId(), value.fulfillmentVersion(),
                value.physicalAllocationId(), value.physicalAllocationVersion(), value.responsibleMembershipId(),
                value.responsibleDisplayName(), value.assignedAt(), value.plannedDispatchAt(),
                value.deliveryId(), value.current());
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static long version(String value) {
        if (value == null || value.isBlank()) {
            throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(
                    "PRECONDITION_REQUIRED", false);
        }
        try {
            long parsed = Long.parseLong(value.replace("\"", ""));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException(
                    "PRECONDITION_REQUIRED", false);
        }
    }

    public record AssignmentRequest(@NotNull UUID responsibleMembershipId,
                                    @NotNull UUID physicalAllocationId,
                                    @NotNull @PositiveOrZero Long physicalAllocationVersion) { }

    public record AssignmentResponse(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                                      UUID physicalAllocationId, long physicalAllocationVersion,
                                      UUID responsibleMembershipId, String responsibleDisplayName,
                                      Instant assignedAt, Instant plannedDispatchAt, UUID deliveryId,
                                      boolean current) { }

    public record PlanChangeRequest(@NotNull UUID expectedAssignmentId,
                                    @NotNull @PositiveOrZero Long expectedAssignmentVersion,
                                    @NotNull UUID physicalAllocationId,
                                    @NotNull @PositiveOrZero Long physicalAllocationVersion,
                                    UUID responsibleMembershipId,
                                    Instant plannedDispatchAt) { }
}
