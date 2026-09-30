package com.nexa.api.inventoryavailability.application.publicapi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only Inventory Availability contract for operational warehouse selection. */
public interface WarehouseSelectionQuery {
    /** Whether a Warehouse identity exists in the supplied tenant/workspace, regardless of operational state. */
    boolean existsInScope(UUID tenantId, UUID workspaceId, UUID warehouseId);

    Optional<WarehouseReference> findOperational(UUID tenantId, UUID workspaceId, UUID warehouseId);

    Optional<WarehouseReference> findPrimaryOperational(UUID tenantId, UUID workspaceId);

    Optional<WarehouseReference> findFulfillable(
            UUID tenantId, UUID workspaceId, Map<UUID, BigDecimal> requestedQuantities);

    Map<UUID, BigDecimal> availability(UUID tenantId, UUID workspaceId, List<UUID> skuIds);

    record WarehouseReference(UUID id, String code, String name, String address, String serviceStatus,
                              int priority, boolean preferred, BigDecimal latitude, BigDecimal longitude) {
    }
}
