package com.nexa.api.salescommitment.infrastructure.warehouse;

import com.nexa.api.salescommitment.application.port.out.WarehouseReferencePort;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** ACL adapter for the warehouse origin used by Sales snapshots. */
@Component
@Profile("!test")
public class WarehouseReferenceAclAdapter implements WarehouseReferencePort {
    private final WarehouseSelectionQuery warehouses;

    public WarehouseReferenceAclAdapter(WarehouseSelectionQuery warehouses) { this.warehouses = warehouses; }

    @Override
    public Optional<WarehouseReference> findActive(String tenantId, String workspaceId, String warehouseId) {
        return warehouses.findOperational(uuid(tenantId), uuid(workspaceId), uuid(warehouseId))
                .map(WarehouseReferenceAclAdapter::reference);
    }

    @Override
    public Optional<WarehouseReference> findPrimary(String tenantId, String workspaceId) {
        return warehouses.findPrimaryOperational(uuid(tenantId), uuid(workspaceId))
                .map(WarehouseReferenceAclAdapter::reference);
    }

    private static WarehouseReference reference(WarehouseSelectionQuery.WarehouseReference value) {
        boolean preferred = value.preferred();
        int priority = value.priority();
        String status = value.serviceStatus();
        String reason = preferred ? "PREFERRED_OPERATIONAL" : priority > 0 ? "PRIORITIZED_OPERATIONAL" : "ACTIVE_OPERATIONAL_FALLBACK";
        return new WarehouseReference(value.id().toString(), value.code(), value.name(), value.address(),
                reason, status, priority, preferred, java.time.Instant.now(), value.latitude(), value.longitude());
    }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
