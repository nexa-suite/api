package com.nexa.api.inventoryavailability.application.port;

import java.time.LocalTime;
import java.util.Optional;

/**
 * Anti-corruption port for workspace operational settings consumed by Warehouse.
 * The Warehouse bounded context never queries tenant-management tables directly.
 */
public interface WarehouseOperationalSettingsPort {
    Optional<Snapshot> find(String tenantId, String workspaceId);

    int update(String tenantId, String workspaceId, String selectionPolicy,
               LocalTime startsAt, LocalTime endsAt, long expectedVersion);

    record Snapshot(String selectionPolicy, String orderCutoffPolicy, String fulfillmentDefaults,
                    String inventoryVisibilityPolicy, String buyerAvailabilityPolicy,
                    LocalTime startsAt, LocalTime endsAt, int orderCutoffMinutes,
                    boolean thermalLogRequired, long version) {
        public Snapshot {
            if (selectionPolicy == null || startsAt == null || endsAt == null || version < 0) {
                throw new IllegalArgumentException("Operational settings snapshot is invalid");
            }
        }
    }
}
