package com.nexa.api.warehouse.infrastructure.persistence;

import com.nexa.api.tenantmanagement.application.service.TenantOperationalSettingsIntegrationService;
import com.nexa.api.warehouse.application.port.WarehouseOperationalSettingsPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Optional;

/**
 * Translates the tenant-management operational-settings port into Warehouse's
 * narrow anti-corruption contract. It deliberately contains no SQL.
 */
@Component
@Profile("!test")
public final class TenantOperationalSettingsAdapter implements WarehouseOperationalSettingsPort {
    private final TenantOperationalSettingsIntegrationService integration;

    public TenantOperationalSettingsAdapter(TenantOperationalSettingsIntegrationService integration) {
        this.integration = integration;
    }

    @Override
    public Optional<Snapshot> find(String tenantId, String workspaceId) {
        return integration.find(workspaceId).map(value -> new Snapshot(value.selectionPolicy(), value.orderCutoffPolicy(),
                value.fulfillmentDefaults(), value.inventoryVisibilityPolicy(), value.buyerAvailabilityPolicy(),
                value.startsAt(), value.endsAt(), value.orderCutoffMinutes(), value.thermalLogRequired(), value.version()));
    }

    @Override
    public int update(String tenantId, String workspaceId, String selectionPolicy,
                      LocalTime startsAt, LocalTime endsAt, long expectedVersion) {
        return integration.update(workspaceId, selectionPolicy, startsAt, endsAt, expectedVersion);
    }
}
