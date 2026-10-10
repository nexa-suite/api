package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Resolves warehouse identity on the currently routed authoritative store. */
@FunctionalInterface
public interface WarehouseSelectionRequestRunner {
    boolean existsInScope(CurrentAccessContext context, UUID warehouseId);
}
