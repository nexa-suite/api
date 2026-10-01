package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels.LineReadiness;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels.Readiness;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchReadinessPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchReadinessPersistencePort.FulfillmentLine;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchReadinessPersistencePort.PickingEvidence;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.AllocationResult;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.Line;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reusable BC-06 current-state evaluation for dispatch-readiness queries and later gates. */
@Service
@Profile("!test")
public class DispatchReadinessService {
    private static final String PREPARED_FULFILLMENT = "PREPARED_FULFILLMENT";
    private static final String READY_FOR_DISPATCH = "READY_FOR_DISPATCH";
    private static final String ALLOCATED = "ALLOCATED";
    private static final String CONFIRMED = "CONFIRMED";

    private final DispatchReadinessPersistencePort query;
    private final PhysicalAllocationCommands physicalAllocations;
    private final WarehouseObjectAccess warehouseAccess;
    private final Clock clock;

    public DispatchReadinessService(DispatchReadinessPersistencePort query,
                                    PhysicalAllocationCommands physicalAllocations,
                                    WarehouseObjectAccess warehouseAccess,
                                    Clock clock) {
        this.query = Objects.requireNonNull(query, "Dispatch-readiness query is required");
        this.physicalAllocations = Objects.requireNonNull(physicalAllocations,
                "Physical allocation facts are required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Warehouse access is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** Filters current Warehouse grants before calculating the response count and page. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DispatchReadinessModels.Page list(CurrentAccessContext context, int page, int size) {
        authorize(context);
        pageCheck(page, size);
        Set<UUID> grantedWarehouses = warehouseAccess.activeWarehouseIds(context);
        if (grantedWarehouses.isEmpty()) {
            return new DispatchReadinessModels.Page(List.of(), page, size, 0, clock.instant());
        }

        List<Readiness> visible = new ArrayList<>();
        for (DispatchReadinessPersistencePort.PreparedFulfillment candidate :
                query.candidates(context.tenantId().value(), context.workspaceId().value())) {
            AllocationResult allocation = authorizedAllocation(context, candidate.id(), grantedWarehouses);
            if (allocation == null) continue;
            visible.add(evaluate(context, candidate, allocation, grantedWarehouses));
        }

        long from = (long) page * size;
        List<Readiness> items = from >= visible.size()
                ? List.of()
                : List.copyOf(visible.subList((int) from, (int) Math.min(from + size, visible.size())));
        return new DispatchReadinessModels.Page(items, page, size, visible.size(), clock.instant());
    }

    /** Loads one prepared Fulfillment inside current tenant, workspace and Warehouse grant scope. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Readiness readiness(CurrentAccessContext context, UUID fulfillmentId) {
        authorize(context);
        return currentReadiness(context, fulfillmentId);
    }

    /** Current allocation and picking facts for a Warehouse fulfillment mutation. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Readiness warehouseReadiness(CurrentAccessContext context, UUID fulfillmentId) {
        Objects.requireNonNull(context, "Verified access context is required")
                .requirePermission(PermissionKey.FULFILLMENT_MANAGE);
        return currentReadiness(context, fulfillmentId);
    }

    private Readiness currentReadiness(CurrentAccessContext context, UUID fulfillmentId) {
        DispatchReadinessPersistencePort.PreparedFulfillment candidate = query.find(
                        context.tenantId().value(), context.workspaceId().value(), fulfillmentId)
                .orElseThrow(DispatchReadinessService::notFound);
        Set<UUID> grantedWarehouses = warehouseAccess.activeWarehouseIds(context);
        AllocationResult allocation = authorizedAllocation(context, candidate.id(), grantedWarehouses);
        if (allocation == null) throw notFound();
        return evaluate(context, candidate, allocation, grantedWarehouses);
    }

    private Readiness evaluate(CurrentAccessContext context,
                               DispatchReadinessPersistencePort.PreparedFulfillment candidate,
                               AllocationResult allocation,
                               Set<UUID> grantedWarehouses) {
        List<PickingEvidence> evidence = query.pickingEvidence(
                context.tenantId().value(), context.workspaceId().value(), candidate.id());
        Map<AllocationKey, BigDecimal> physicalByLine = new HashMap<>();
        Map<UUID, Line> allocationLinesById = new HashMap<>();
        boolean allocationComplete = candidate.physicalAllocationId() != null
                && candidate.physicalAllocationId().equals(allocation.allocationId())
                && ALLOCATED.equals(allocation.status())
                && allocation.version() >= 0
                && !allocation.lines().isEmpty();
        Set<AllocationKey> fulfillmentLineKeys = new HashSet<>();
        for (FulfillmentLine fulfillmentLine : candidate.lines()) {
            fulfillmentLineKeys.add(key(fulfillmentLine));
        }
        for (Line allocationLine : allocation.lines()) {
            if (allocationLine.id() == null || allocationLine.warehouseId() == null
                    || allocationLine.lotId() == null || allocationLine.quantity() == null
                    || allocationLine.releasedQuantity() == null || allocationLine.consumedQuantity() == null
                    || !grantedWarehouses.contains(allocationLine.warehouseId())) {
                allocationComplete = false;
                continue;
            }
            AllocationKey lineKey = key(allocationLine);
            if (!fulfillmentLineKeys.contains(lineKey)) allocationComplete = false;
            BigDecimal effectiveQuantity = allocationLine.quantity()
                    .subtract(allocationLine.releasedQuantity())
                    .subtract(allocationLine.consumedQuantity());
            if (effectiveQuantity.signum() < 0) allocationComplete = false;
            physicalByLine.merge(lineKey, effectiveQuantity, BigDecimal::add);
            allocationLinesById.put(allocationLine.id(), allocationLine);
        }

        Map<UUID, BigDecimal> pickedEvidenceByLine = new HashMap<>();
        Map<UUID, BigDecimal> evidenceByAllocationLine = new HashMap<>();
        Map<UUID, FulfillmentLine> fulfillmentLinesById = new HashMap<>();
        candidate.lines().forEach(line -> fulfillmentLinesById.put(line.id(), line));
        boolean evidenceReferencesCurrentAllocation = true;
        for (PickingEvidence fact : evidence) {
            FulfillmentLine fulfillmentLine = fulfillmentLinesById.get(fact.fulfillmentLineId());
            Line allocationLine = allocationLinesById.get(fact.physicalAllocationLineId());
            boolean currentReference = fulfillmentLine != null
                    && allocationLine != null
                    && key(fulfillmentLine).equals(key(allocationLine))
                    && fact.lotId() != null && fact.lotId().equals(allocationLine.lotId())
                    && fact.warehouseId() != null && fact.warehouseId().equals(allocationLine.warehouseId())
                    && grantedWarehouses.contains(fact.warehouseId())
                    && fact.quantity() != null && fact.quantity().signum() > 0
                    && CONFIRMED.equals(fact.resultStatus());
            if (!currentReference) evidenceReferencesCurrentAllocation = false;
            if (fulfillmentLine != null && fact.quantity() != null) {
                pickedEvidenceByLine.merge(fact.fulfillmentLineId(), fact.quantity(), BigDecimal::add);
            }
            if (allocationLine != null && fact.quantity() != null) {
                evidenceByAllocationLine.merge(fact.physicalAllocationLineId(), fact.quantity(), BigDecimal::add);
            }
        }

        for (Map.Entry<UUID, BigDecimal> picked : evidenceByAllocationLine.entrySet()) {
            Line allocationLine = allocationLinesById.get(picked.getKey());
            if (allocationLine == null) {
                evidenceReferencesCurrentAllocation = false;
                continue;
            }
            BigDecimal effectiveQuantity = allocationLine.quantity()
                    .subtract(allocationLine.releasedQuantity())
                    .subtract(allocationLine.consumedQuantity());
            if (picked.getValue().compareTo(effectiveQuantity) > 0) {
                evidenceReferencesCurrentAllocation = false;
            }
        }

        List<LineReadiness> lines = new ArrayList<>();
        boolean pickingComplete = !candidate.lines().isEmpty();
        boolean pickingEvidenceComplete = !candidate.lines().isEmpty() && evidenceReferencesCurrentAllocation;
        for (FulfillmentLine line : candidate.lines()) {
            BigDecimal physicallyAllocated = physicalByLine.getOrDefault(key(line), BigDecimal.ZERO);
            BigDecimal evidencedPicked = pickedEvidenceByLine.getOrDefault(line.id(), BigDecimal.ZERO);
            boolean lineAllocationComplete = line.allocatedQuantity() != null
                    && line.allocatedQuantity().signum() > 0
                    && physicallyAllocated.compareTo(line.allocatedQuantity()) == 0;
            boolean linePickingComplete = line.allocatedQuantity() != null
                    && line.pickedQuantity() != null
                    && line.allocatedQuantity().compareTo(line.pickedQuantity()) == 0;
            boolean lineEvidenceComplete = linePickingComplete
                    && line.pickedQuantity() != null
                    && line.pickedQuantity().signum() > 0
                    && line.pickedQuantity().compareTo(evidencedPicked) == 0;
            allocationComplete &= lineAllocationComplete;
            pickingComplete &= linePickingComplete;
            pickingEvidenceComplete &= lineEvidenceComplete;
            lines.add(new LineReadiness(line.id(), line.skuId(), line.catalogItemId(),
                    line.allocatedQuantity(), physicallyAllocated, line.pickedQuantity(), evidencedPicked,
                    lineAllocationComplete, linePickingComplete, lineEvidenceComplete));
        }

        List<String> reasons = new ArrayList<>();
        if (!READY_FOR_DISPATCH.equals(candidate.status())) {
            reasons.add("FULFILLMENT_NOT_READY_FOR_DISPATCH");
        }
        if (!allocationComplete) reasons.add("PHYSICAL_ALLOCATION_NOT_CURRENT");
        if (!pickingComplete) reasons.add("PICKING_INCOMPLETE");
        if (!pickingEvidenceComplete) reasons.add("PICKING_EVIDENCE_INCOMPLETE");
        Instant asOf = clock.instant();
        return new Readiness(PREPARED_FULFILLMENT, candidate.id(), candidate.version(), candidate.status(),
                allocation.allocationId(), allocation.status(), allocation.version(), candidate.deliveryId(),
                candidate.deliveryStatus(), candidate.deliveryVersion(), allocationComplete, pickingComplete,
                pickingEvidenceComplete, reasons.isEmpty(), reasons, lines, asOf);
    }

    private AllocationResult authorizedAllocation(CurrentAccessContext context, UUID fulfillmentId,
                                                    Set<UUID> grantedWarehouses) {
        if (grantedWarehouses.isEmpty()) return null;
        AllocationResult allocation;
        try {
            allocation = physicalAllocations.getByFulfillment(context.tenantId().value(),
                    context.workspaceId().value(), fulfillmentId, context.membershipId().value());
        } catch (WarehouseOperationException exception) {
            if (exception.notFound()) return null;
            throw exception;
        }
        if (allocation == null || allocation.lines().isEmpty()
                || allocation.lines().stream().anyMatch(line -> line.warehouseId() == null
                || !grantedWarehouses.contains(line.warehouseId()))) {
            return null;
        }
        return allocation;
    }

    private static AllocationKey key(FulfillmentLine line) {
        return new AllocationKey(line.skuId(), line.catalogItemId(), line.unit());
    }

    private static AllocationKey key(Line line) {
        return new AllocationKey(line.skuId(), line.catalogItemId(), line.unit());
    }

    private static void authorize(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required")
                .requirePermission(PermissionKey.DISPATCH_READ);
    }

    private static void pageCheck(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
    }

    private static FulfillmentOperationException notFound() {
        return new FulfillmentOperationException("FULFILLMENT_NOT_FOUND", true);
    }

    private record AllocationKey(UUID skuId, String catalogItemId, String unit) { }
}
