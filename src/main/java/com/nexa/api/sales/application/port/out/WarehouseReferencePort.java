package com.nexa.api.sales.application.port.out;

import java.util.Optional;

/** Narrow ACL for a warehouse origin snapshot. No Warehouse entity crosses into Sales. */
public interface WarehouseReferencePort {
    Optional<WarehouseReference> findActive(String tenantId, String workspaceId, String warehouseId);

    Optional<WarehouseReference> findPrimary(String tenantId, String workspaceId);

    record WarehouseReference(String id, String code, String name, String address) { }
}
