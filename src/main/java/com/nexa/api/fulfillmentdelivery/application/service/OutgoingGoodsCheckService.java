package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels.Readiness;
import com.nexa.api.fulfillmentdelivery.application.model.OutgoingGoodsCheckModels;
import com.nexa.api.fulfillmentdelivery.application.model.OutgoingGoodsCheckModels.Check;
import com.nexa.api.fulfillmentdelivery.application.model.OutgoingGoodsCheckModels.Observation;
import com.nexa.api.fulfillmentdelivery.application.model.OutgoingGoodsCheckModels.Request;
import com.nexa.api.fulfillmentdelivery.application.port.OutgoingGoodsCheckPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.OutgoingGoodsCheckPersistencePort.LineFact;
import com.nexa.api.fulfillmentdelivery.application.port.OutgoingGoodsCheckPersistencePort.RecordRequest;
import com.nexa.api.fulfillmentdelivery.application.port.OutgoingGoodsCheckPersistencePort.StoredCheck;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.AllocationResult;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.Line;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Compares checked goods with the current allocation without mutating inventory. */
@Service
@Profile("!test")
public class OutgoingGoodsCheckService {
    private static final String READY_FOR_DISPATCH = "READY_FOR_DISPATCH";

    private final DispatchReadinessService readiness;
    private final PhysicalAllocationCommands physicalAllocations;
    private final OutgoingGoodsCheckPersistencePort checks;
    private final Clock clock;

    public OutgoingGoodsCheckService(DispatchReadinessService readiness,
                                    PhysicalAllocationCommands physicalAllocations,
                                    OutgoingGoodsCheckPersistencePort checks,
                                    Clock clock) {
        this.readiness = Objects.requireNonNull(readiness, "Current dispatch readiness is required");
        this.physicalAllocations = Objects.requireNonNull(physicalAllocations,
                "Current physical allocation is required");
        this.checks = Objects.requireNonNull(checks, "Outgoing-check persistence is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Check record(CurrentAccessContext context, UUID fulfillmentId,
                        long expectedFulfillmentVersion, String idempotencyKey,
                        Request request) {
        authorize(context);
        requireKey(idempotencyKey);
        if (request == null || request.physicalAllocationId() == null ||
                request.physicalAllocationVersion() < 0 || request.observations().isEmpty()) {
            throw invalid("FULFILLMENT_OUTGOING_CHECK_INVALID");
        }
        if (request.observations().stream().anyMatch(observation -> observation == null ||
                observation.physicalAllocationLineId() == null || observation.observedQuantity() == null ||
                observation.observedQuantity().signum() < 0 ||
                (observation.observedQuantity().signum() == 0 && observation.observedLotId() != null) ||
                (observation.observedQuantity().signum() > 0 && observation.observedLotId() == null))) {
            throw invalid("FULFILLMENT_OUTGOING_CHECK_LINES_INVALID");
        }
        physicalAllocations.lockForFulfillment(tenant(context), workspace(context), fulfillmentId, actor(context));
        Readiness current = readiness.warehouseReadiness(context, fulfillmentId);
        requireCurrentSnapshot(current, expectedFulfillmentVersion,
                request.physicalAllocationId(), request.physicalAllocationVersion());
        if (!READY_FOR_DISPATCH.equals(current.fulfillmentStatus()) || !current.ready()) {
            throw conflict("FULFILLMENT_NOT_READY_FOR_DISPATCH");
        }

        AllocationResult allocation = physicalAllocations.getByFulfillment(
                tenant(context), workspace(context), fulfillmentId, actor(context));
        requireAllocation(current, allocation, request);
        List<Observation> observations = request.observations().stream()
                .sorted(Comparator.comparing(Observation::physicalAllocationLineId))
                .toList();
        Map<UUID, Observation> observedByLine = observations.stream().collect(Collectors.toMap(
                Observation::physicalAllocationLineId, Function.identity()));
        if (observedByLine.size() != observations.size() ||
                !observedByLine.keySet().equals(allocation.lines().stream()
                        .map(Line::id).collect(Collectors.toSet()))) {
            throw invalid("FULFILLMENT_OUTGOING_CHECK_LINES_INVALID");
        }

        List<LineFact> lines = new ArrayList<>();
        for (Line allocated : allocation.lines()) {
            Observation observed = observedByLine.get(allocated.id());
            BigDecimal expected = allocated.quantity().subtract(allocated.releasedQuantity())
                    .subtract(allocated.consumedQuantity());
            boolean matches = expected.compareTo(observed.observedQuantity()) == 0 &&
                    Objects.equals(allocated.lotId(), observed.observedLotId());
            lines.add(new LineFact(allocated.id(), allocated.skuId(), allocated.lotId(),
                    observed.observedLotId(), expected, observed.observedQuantity(), allocated.unit(), matches));
        }

        boolean matches = lines.stream().allMatch(LineFact::matches);
        String hash = requestHash(fulfillmentId, expectedFulfillmentVersion, allocation.allocationId(),
                allocation.version(), observations);
        OutgoingGoodsCheckPersistencePort.PersistResult result = checks.record(new RecordRequest(
                tenant(context), workspace(context), fulfillmentId, expectedFulfillmentVersion,
                allocation.allocationId(), allocation.version(), actor(context), idempotencyKey.trim(),
                hash, matches, clock.instant(), lines));
        if (result.keyConflict() || result.check() == null) {
            throw conflict("IDEMPOTENCY_PAYLOAD_CONFLICT");
        }
        return project(context, result.check(), true, result.replayed());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Check current(CurrentAccessContext context, UUID fulfillmentId) {
        authorize(context);
        Readiness current = readiness.warehouseReadiness(context, fulfillmentId);
        StoredCheck stored = checks.latest(tenant(context), workspace(context), fulfillmentId).orElse(null);
        if (stored == null) return null;
        boolean currentVersion = stored.fulfillmentVersion() == current.fulfillmentVersion() &&
                stored.physicalAllocationId().equals(current.physicalAllocationId()) &&
                stored.physicalAllocationVersion() == current.physicalAllocationVersion();
        boolean openDiscrepancy = currentVersion && checks.hasOpenDiscrepancy(
                tenant(context), workspace(context), fulfillmentId,
                current.physicalAllocationId(), current.physicalAllocationVersion());
        return project(stored, currentVersion, openDiscrepancy, false);
    }

    /** Must be called in the same transaction after the allocation lock is held. */
    public UUID requireCurrentMatch(CurrentAccessContext context, UUID fulfillmentId,
                                    long fulfillmentVersion, UUID allocationId,
                                    long allocationVersion) {
        UUID currentMatchId = checks.currentMatchId(tenant(context), workspace(context), fulfillmentId,
                fulfillmentVersion, allocationId, allocationVersion).orElse(null);
        if (currentMatchId == null) {
            if (checks.hasOpenDiscrepancy(tenant(context), workspace(context), fulfillmentId,
                    allocationId, allocationVersion)) {
                throw conflict("FULFILLMENT_OUTGOING_DISCREPANCY_OPEN");
            }
            throw conflict("FULFILLMENT_OUTGOING_CHECK_REQUIRED");
        }
        return currentMatchId;
    }

    private Check project(CurrentAccessContext context, StoredCheck stored,
                          boolean current, boolean replayed) {
        boolean openDiscrepancy = current && checks.hasOpenDiscrepancy(
                tenant(context), workspace(context), stored.fulfillmentId(),
                stored.physicalAllocationId(), stored.physicalAllocationVersion());
        return project(stored, current, openDiscrepancy, replayed);
    }

    private static Check project(StoredCheck stored, boolean current,
                                 boolean openDiscrepancy, boolean replayed) {
        return new Check(stored.id(), stored.fulfillmentId(), stored.fulfillmentVersion(),
                stored.physicalAllocationId(), stored.physicalAllocationVersion(), stored.matches(),
                current, openDiscrepancy, stored.checkedByMembershipId(), stored.checkedAt(),
                stored.lines().stream().map(line -> new OutgoingGoodsCheckModels.Line(line.physicalAllocationLineId(),
                        line.skuId(), line.expectedLotId(), line.observedLotId(),
                        line.expectedQuantity(), line.observedQuantity(), line.unit(), line.matches())).toList(),
                replayed);
    }

    private static void requireCurrentSnapshot(Readiness current, long expectedFulfillmentVersion,
                                               UUID allocationId, long allocationVersion) {
        if (current.fulfillmentVersion() != expectedFulfillmentVersion ||
                !Objects.equals(current.physicalAllocationId(), allocationId) ||
                current.physicalAllocationVersion() != allocationVersion) {
            throw conflict("FULFILLMENT_CONCURRENCY_CONFLICT");
        }
    }

    private static void requireAllocation(Readiness readiness, AllocationResult allocation,
                                          Request request) {
        if (allocation == null || allocation.lines().isEmpty() ||
                !Objects.equals(readiness.physicalAllocationId(), allocation.allocationId()) ||
                allocation.version() != request.physicalAllocationVersion() ||
                !Objects.equals(request.physicalAllocationId(), allocation.allocationId()) ||
                allocation.lines().stream().anyMatch(line -> line.id() == null || line.lotId() == null ||
                        line.skuId() == null || line.quantity() == null || line.releasedQuantity() == null ||
                        line.consumedQuantity() == null || line.unit() == null || line.unit().isBlank())) {
            throw conflict("FULFILLMENT_CONCURRENCY_CONFLICT");
        }
    }

    private static String requestHash(UUID fulfillmentId, long fulfillmentVersion,
                                      UUID allocationId, long allocationVersion,
                                      List<Observation> observations) {
        StringBuilder value = new StringBuilder("outgoing-goods-check-v1|")
                .append(fulfillmentId).append('|').append(fulfillmentVersion).append('|')
                .append(allocationId).append('|').append(allocationVersion);
        observations.stream().sorted(Comparator.comparing(Observation::physicalAllocationLineId))
                .forEach(line -> value.append('|').append(line.physicalAllocationLineId())
                        .append(':').append(line.observedLotId()).append(':')
                        .append(line.observedQuantity().stripTrailingZeros().toPlainString()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void authorize(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required")
                .requirePermission(PermissionKey.FULFILLMENT_MANAGE);
    }

    private static UUID tenant(CurrentAccessContext context) { return context.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext context) { return context.workspaceId().value(); }
    private static UUID actor(CurrentAccessContext context) { return context.membershipId().value(); }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new FulfillmentOperationException("PRECONDITION_REQUIRED", false);
        }
    }

    private static FulfillmentOperationException invalid(String code) {
        return new FulfillmentOperationException(code, false);
    }

    private static FulfillmentOperationException conflict(String code) {
        return new FulfillmentOperationException(code, false);
    }
}
