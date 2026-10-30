package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentWorkListModels;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchReadinessPersistencePort;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.AllocationResult;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Builds a picking work page only after current warehouse grants are applied. */
@Service
@Profile("!test")
public class FulfillmentWorkListService implements com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentWorkListQueries {
    private static final Set<String> WAREHOUSE_WORK_STATES =
            Set.of("ALLOCATED", "PICKING", "READY_FOR_DISPATCH");
    private static final String ALLOCATED = "ALLOCATED";

    private final DispatchReadinessPersistencePort fulfillments;
    private final PhysicalAllocationCommands allocations;
    private final WarehouseObjectAccess warehouseAccess;
    private final Clock clock;
    private final com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery orders;

    public FulfillmentWorkListService(DispatchReadinessPersistencePort fulfillments,
                                      PhysicalAllocationCommands allocations, WarehouseObjectAccess warehouseAccess,
                                      Clock clock) {
        this(fulfillments, allocations, warehouseAccess, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FulfillmentWorkListService(DispatchReadinessPersistencePort fulfillments,
                                      PhysicalAllocationCommands allocations, WarehouseObjectAccess warehouseAccess,
                                      Clock clock, com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery orders) {
        this.fulfillments = Objects.requireNonNull(fulfillments, "Fulfillment query is required");
        this.allocations = Objects.requireNonNull(allocations, "Physical allocation facts are required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Warehouse access is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.orders = orders;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FulfillmentWorkListModels.Page list(CurrentAccessContext context, int page, int size) {
        Objects.requireNonNull(context, "Verified access context is required")
                .requirePermission(PermissionKey.FULFILLMENT_READ);
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }

        Set<UUID> grantedWarehouses = warehouseAccess.activeWarehouseIds(context);
        if (grantedWarehouses.isEmpty()) {
            return new FulfillmentWorkListModels.Page(List.of(), page, size, 0, clock.instant());
        }

        List<FulfillmentWorkListModels.Item> visible = new ArrayList<>();
        for (DispatchReadinessPersistencePort.PreparedFulfillment candidate :
                fulfillments.candidates(context.tenantId().value(), context.workspaceId().value())) {
            if (!WAREHOUSE_WORK_STATES.contains(candidate.status())
                    || candidate.physicalAllocationId() == null || candidate.lines().isEmpty()) {
                continue;
            }
            AllocationResult allocation = currentGrantedAllocation(context, candidate.id());
            if (allocation == null || !candidate.physicalAllocationId().equals(allocation.allocationId())
                    || !ALLOCATED.equals(allocation.status()) || allocation.version() < 0
                    || allocation.lines().isEmpty()
                    || allocation.lines().stream().anyMatch(line -> line.id() == null || line.warehouseId() == null
                    || !grantedWarehouses.contains(line.warehouseId()))) {
                continue;
            }
            visible.add(new FulfillmentWorkListModels.Item(candidate.id(), candidate.salesOrderId(),
                    candidate.status(), candidate.version(), allocation.allocationId(), allocation.version(),
                    candidate.lines().size()));
        }

        long from = (long) page * size;
        List<FulfillmentWorkListModels.Item> items = from >= visible.size()
                ? List.of()
                : List.copyOf(visible.subList((int) from, (int) Math.min(from + size, visible.size())));
        if (orders != null && !items.isEmpty()) {
            var headers = orders.findHeaders(context.tenantId().value(), context.workspaceId().value(),
                    items.stream().map(FulfillmentWorkListModels.Item::salesOrderId).distinct().toList());
            items = items.stream().map(item -> {
                var header = headers.get(item.salesOrderId());
                return item.withOrderNumber(header == null ? null : header.number());
            }).toList();
        }
        return new FulfillmentWorkListModels.Page(items, page, size, visible.size(), clock.instant());
    }

    private AllocationResult currentGrantedAllocation(CurrentAccessContext context, UUID fulfillmentId) {
        try {
            return allocations.getByFulfillment(context.tenantId().value(), context.workspaceId().value(),
                    fulfillmentId, context.membershipId().value());
        } catch (WarehouseOperationException exception) {
            if (exception.notFound()) return null;
            throw exception;
        }
    }
}
