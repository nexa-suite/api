package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels.Readiness;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchWindowPlanModels.Request;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchWindowPlanModels.View;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchWindowPlanPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchWindowPlanPersistencePort.RecordCommand;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Dispatch-authorized, one-way planning of a missing Fulfillment delivery window. */
@Service
@Profile("!test")
public class DispatchWindowPlanService {
    private final DispatchWindowPlanPersistencePort persistence;
    private final DispatchReadinessService readiness;
    private final PhysicalAllocationCommands physicalAllocations;
    private final Clock clock;

    public DispatchWindowPlanService(DispatchWindowPlanPersistencePort persistence,
                                    DispatchReadinessService readiness,
                                    PhysicalAllocationCommands physicalAllocations,
                                    Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "Dispatch-window persistence is required");
        this.readiness = Objects.requireNonNull(readiness, "Dispatch readiness is required");
        this.physicalAllocations = Objects.requireNonNull(physicalAllocations, "Physical allocations are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public View plan(CurrentAccessContext context, UUID fulfillmentId, long expectedFulfillmentVersion,
                     Request request, String idempotencyKey) {
        context.requirePermission(PermissionKey.DISPATCH_READ);
        context.requirePermission(PermissionKey.DISPATCH_SCHEDULE);
        validate(fulfillmentId, expectedFulfillmentVersion, request, idempotencyKey);

        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        UUID actorMembershipId = context.membershipId().value();
        // Preserve the established Inventory-allocation then Fulfillment lock order used by load planning.
        physicalAllocations.lockForFulfillment(tenantId, workspaceId, fulfillmentId, actorMembershipId);
        Readiness current = readiness.readiness(context, fulfillmentId);

        String reason = request.reason().trim();
        String requestHash = hash("fulfillment-dispatch-window-plan-v1|" + fulfillmentId + "|"
                + expectedFulfillmentVersion + "|" + request.windowStart() + "|" + request.windowEnd()
                + "|" + reason);
        Optional<View> replay = persistence.findReplay(tenantId, workspaceId, actorMembershipId,
                idempotencyKey, requestHash);
        if (replay.isPresent()) return replay.get();
        if (!current.ready() || !"READY_FOR_DISPATCH".equals(current.fulfillmentStatus())) {
            throw error("FULFILLMENT_NOT_READY_FOR_DISPATCH");
        }

        return persistence.record(new RecordCommand(tenantId, workspaceId, fulfillmentId, actorMembershipId,
                expectedFulfillmentVersion, request.windowStart(), request.windowEnd(), reason,
                idempotencyKey, requestHash, clock.instant()));
    }

    private static void validate(UUID fulfillmentId, long expectedVersion, Request request, String key) {
        if (fulfillmentId == null || expectedVersion < 0 || request == null || request.windowStart() == null
                || request.windowEnd() == null || !request.windowStart().isBefore(request.windowEnd())) {
            throw error("INVALID_REQUEST");
        }
        if (request.reason() == null || request.reason().isBlank() || request.reason().trim().length() > 2000) {
            throw error("INVALID_REQUEST");
        }
        if (key == null || key.isBlank() || key.length() > 160) throw error("IDEMPOTENCY_KEY_REQUIRED");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static FulfillmentOperationException error(String code) {
        return new FulfillmentOperationException(code, false);
    }
}
