package com.nexa.api.inventoryavailability.application.service;

import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/** Application boundary for read-only physical allocation scan validation. */
@Service
@Profile("!test")
public class PhysicalScanValidationService {
    private final PhysicalAllocationCommands allocations;
    private final Clock clock;

    public PhysicalScanValidationService(PhysicalAllocationCommands allocations, Clock clock) {
        this.allocations = Objects.requireNonNull(allocations, "Physical allocations are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    // The standalone HTTP contract is deliberately read-only. A FEFO
    // override is a mutation and is only accepted through the idempotent
    // BC-06 picking confirmation orchestration below the public contract.
    @Transactional
    public PhysicalAllocationCommands.PickingScanValidationResult validate(
            CurrentAccessContext context, PhysicalAllocationCommands.PickingScanValidationRequest request) {
        context.requirePermission(PermissionKey.INVENTORY_READ);
        if (!context.tenantId().value().equals(request.tenantId())
                || !context.workspaceId().value().equals(request.workspaceId())) {
            throw new WarehouseOperationsService.WarehouseException("FORBIDDEN", false);
        }
        return allocations.validatePickingScan(new PhysicalAllocationCommands.PickingScanValidationRequest(
                request.tenantId(), request.workspaceId(), request.fulfillmentId(), request.physicalAllocationLineId(),
                request.skuId(), request.lotId(), request.warehouseId(), request.quantity(), request.unit(),
                request.expectedAllocationVersion(), clock.instant(), context.membershipId().value(),
                false, null));
    }
}
