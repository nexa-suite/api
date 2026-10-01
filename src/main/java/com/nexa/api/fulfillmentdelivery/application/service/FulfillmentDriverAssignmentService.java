package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort.AssignDriverRequest;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort.FulfillmentDriverAssignmentView;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Current-authority command for assigning an eligible Logistics member to prepared Fulfillment work. */
@Service
@Profile("!test")
public class FulfillmentDriverAssignmentService {
    private final DispatchReadinessService readiness;
    private final PhysicalAllocationCommands physicalAllocations;
    private final WorkforceDirectory workforce;
    private final FulfillmentPersistencePort fulfillments;
    private final BusinessTraceabilityCommands traceability;
    private final Clock clock;

    public FulfillmentDriverAssignmentService(DispatchReadinessService readiness,
                                              PhysicalAllocationCommands physicalAllocations,
                                              WorkforceDirectory workforce,
                                              FulfillmentPersistencePort fulfillments,
                                              BusinessTraceabilityCommands traceability,
                                              Clock clock) {
        this.readiness = Objects.requireNonNull(readiness, "Dispatch readiness is required");
        this.physicalAllocations = Objects.requireNonNull(physicalAllocations, "Physical allocation commands are required");
        this.workforce = Objects.requireNonNull(workforce, "Current Logistics workforce is required");
        this.fulfillments = Objects.requireNonNull(fulfillments, "Fulfillment persistence is required");
        this.traceability = Objects.requireNonNull(traceability, "Business traceability is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public FulfillmentDriverAssignmentView assign(CurrentAccessContext context, UUID fulfillmentId,
                                                  long expectedFulfillmentVersion, UUID expectedAllocationId,
                                                  long expectedAllocationVersion, UUID responsibleMembershipId,
                                                  String idempotencyKey) {
        context.requirePermission(PermissionKey.DISPATCH_ASSIGN);
        requireKey(idempotencyKey);
        if (fulfillmentId == null || expectedFulfillmentVersion < 0 || expectedAllocationId == null
                || expectedAllocationVersion < 0 || responsibleMembershipId == null) {
            throw invalid("INVALID_REQUEST");
        }

        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        UUID actorMembershipId = context.membershipId().value();
        physicalAllocations.lockForFulfillment(tenantId, workspaceId, fulfillmentId, actorMembershipId);

        // Both current warehouse grant and dispatch.read authority are checked before persistence replay.
        var current = readiness.readiness(context, fulfillmentId);
        WorkforceDirectory.LogisticsAssignee responsible = workforce.findLogisticsAssignees(tenantId, workspaceId)
                .stream().filter(candidate -> candidate.id().equals(responsibleMembershipId))
                .findFirst().orElseThrow(() -> invalid("RESPONSIBLE_MEMBERSHIP_INVALID"));

        Instant now = clock.instant();
        String requestHash = hash("fulfillment-driver-assignment-v1|" + fulfillmentId + "|"
                + expectedFulfillmentVersion + "|" + expectedAllocationId + "|" + expectedAllocationVersion
                + "|" + responsibleMembershipId);
        FulfillmentDriverAssignmentView result = fulfillments.assignDriver(new AssignDriverRequest(
                tenantId, workspaceId, fulfillmentId, expectedFulfillmentVersion, expectedAllocationId,
                expectedAllocationVersion, current.fulfillmentVersion(), current.fulfillmentStatus(), current.ready(),
                current.physicalAllocationId(), current.physicalAllocationVersion(), responsible.id(),
                responsible.userId(), responsible.displayName(), actorMembershipId, idempotencyKey,
                requestHash, now));
        trace(context, fulfillmentId, responsible.id(), idempotencyKey, now);
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<FulfillmentDriverAssignmentView> current(CurrentAccessContext context, UUID fulfillmentId) {
        readiness.readiness(context, fulfillmentId);
        return fulfillments.findDriverAssignment(context.tenantId().value(), context.workspaceId().value(), fulfillmentId);
    }

    private void trace(CurrentAccessContext context, UUID fulfillmentId, UUID responsibleMembershipId,
                       String idempotencyKey, Instant now) {
        traceability.record(new BusinessTraceabilityCommands.TraceRequest(
                context.tenantId().value(), context.workspaceId().value(), context.membershipId().value(),
                "FULFILLMENT_DELIVERY", "FULFILLMENT_DRIVER_ASSIGNED", "Fulfillment", fulfillmentId,
                idempotencyKey, operationKey("trace-", idempotencyKey),
                Map.of("responsibleMembershipId", responsibleMembershipId), now));
    }

    private static String operationKey(String prefix, String value) {
        return prefix + hash(value);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) throw invalid("IDEMPOTENCY_KEY_REQUIRED");
    }

    private static FulfillmentOperationException invalid(String code) {
        return new FulfillmentOperationException(code, code != null && code.endsWith("_NOT_FOUND"));
    }
}
