package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.TenantConfigurationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.OperationalSettings;

import java.time.LocalTime;
import java.util.Optional;

/**
 * Exposed anti-corruption service for operational consumers. TenantManagement
 * owns the persistence port and domain value; consumers receive only this
 * immutable integration snapshot, never tenant-management SQL or entities.
 */
public final class TenantOperationalSettingsIntegrationService {
    private final TenantConfigurationPort configuration;

    public TenantOperationalSettingsIntegrationService(TenantConfigurationPort configuration) {
        this.configuration = configuration;
    }

    public Optional<Snapshot> find(String workspaceId) {
        return configuration.findOperationalSettings(workspaceId)
                .map(TenantOperationalSettingsIntegrationService::snapshot);
    }

    public int update(String workspaceId, String selectionPolicy, LocalTime startsAt,
                      LocalTime endsAt, long expectedVersion) {
        OperationalSettings current = configuration.findOperationalSettings(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Operational settings are not configured"));
        OperationalSettings updated = new OperationalSettings(selectionPolicy, current.orderCutoffPolicy(),
                current.fulfillmentDefaults(), current.inventoryVisibilityPolicy(), current.buyerAvailabilityPolicy(),
                startsAt, endsAt, current.orderCutoffMinutes(), current.thermalLogRequired(), expectedVersion);
        return configuration.updateOperationalSettings(workspaceId, updated);
    }

    private static Snapshot snapshot(OperationalSettings value) {
        return new Snapshot(value.defaultWarehouseSelectionPolicy(), value.orderCutoffPolicy(),
                value.fulfillmentDefaults(), value.inventoryVisibilityPolicy(), value.buyerAvailabilityPolicy(),
                value.operatingHoursStart(), value.operatingHoursEnd(), value.orderCutoffMinutes(),
                value.thermalLogRequired(), value.version());
    }

    public record Snapshot(String selectionPolicy, String orderCutoffPolicy, String fulfillmentDefaults,
                           String inventoryVisibilityPolicy, String buyerAvailabilityPolicy,
                           LocalTime startsAt, LocalTime endsAt, int orderCutoffMinutes,
                           boolean thermalLogRequired, long version) { }
}
