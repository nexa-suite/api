package com.nexa.api.salescommitment.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Narrow ACL for a warehouse origin snapshot. No Warehouse entity crosses into Sales. */
public interface WarehouseReferencePort {
    Optional<WarehouseReference> findActive(String tenantId, String workspaceId, String warehouseId);

    Optional<WarehouseReference> findPrimary(String tenantId, String workspaceId);

    record WarehouseReference(String id, String code, String name, String address,
                              String selectionReason, String serviceStatus, int priority,
                              boolean preferred, Instant selectedAt,
                              BigDecimal latitude, BigDecimal longitude) {
        public WarehouseReference(String id, String code, String name, String address) {
            this(id, code, name, address, "ACTIVE_OPERATIONAL_FALLBACK", "OPERATIONAL", 0, false,
                    Instant.now(), null, null);
        }
    }
}
