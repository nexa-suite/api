package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service;

import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.TenantConfigurationModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.TenantConfigurationUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseOperationalSettingsCommands;

import java.time.LocalTime;
import java.util.Objects;

/** Keeps Warehouse settings writes inside BC-01's central configuration use case. */
public final class WarehouseOperationalSettingsCommandService implements WarehouseOperationalSettingsCommands {
    private final TenantConfigurationUseCase configuration;
    private final OperationalSettingsAccess settings;

    public WarehouseOperationalSettingsCommandService(TenantConfigurationUseCase configuration,
                                                       OperationalSettingsAccess settings) {
        this.configuration = Objects.requireNonNull(configuration, "Tenant configuration use case is required");
        this.settings = Objects.requireNonNull(settings, "Operational settings access is required");
    }

    @Override
    public Snapshot replaceHours(CurrentAccessContext context, LocalTime startsAt, LocalTime endsAt,
                                 long expectedSettingsVersion, String correlationId) {
        OperationalSettingsAccess.Snapshot current = current(context);
        LocalTime updatedStart = startsAt == null ? current.startsAt() : startsAt;
        LocalTime updatedEnd = endsAt == null ? current.endsAt() : endsAt;
        return update(context, current, current.selectionPolicy(), updatedStart, updatedEnd,
                expectedSettingsVersion, correlationId);
    }

    @Override
    public Snapshot replaceSelectionPolicy(CurrentAccessContext context, String selectionPolicy,
                                           long expectedSettingsVersion, String correlationId) {
        OperationalSettingsAccess.Snapshot current = current(context);
        String normalized = selectionPolicy == null ? current.selectionPolicy()
                : selectionPolicy.strip().toUpperCase(java.util.Locale.ROOT);
        return update(context, current, normalized, current.startsAt(), current.endsAt(),
                expectedSettingsVersion, correlationId);
    }

    private Snapshot update(CurrentAccessContext context, OperationalSettingsAccess.Snapshot current,
                            String selectionPolicy, LocalTime startsAt, LocalTime endsAt,
                            long expectedSettingsVersion, String correlationId) {
        String workspaceId = context.workspaceId().toString();
        TenantConfigurationModels.OperationalSettingsView request = new TenantConfigurationModels.OperationalSettingsView(
                workspaceId, selectionPolicy, current.orderCutoffPolicy(), current.fulfillmentDefaults(),
                current.inventoryVisibilityPolicy(), current.buyerAvailabilityPolicy(), startsAt, endsAt,
                current.orderCutoffMinutes(), current.thermalLogRequired(), expectedSettingsVersion);
        TenantConfigurationModels.OperationalSettingsView updated = configuration.updateOperationalSettings(
                context, workspaceId, request, expectedSettingsVersion, correlationId);
        return new Snapshot(updated.defaultWarehouseSelectionPolicy(), updated.operatingHoursStart(),
                updated.operatingHoursEnd(), updated.version());
    }

    private OperationalSettingsAccess.Snapshot current(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
        return settings.find(context.workspaceId().toString())
                .orElseThrow(() -> new ApiResourceNotFoundException("operational settings"));
    }
}
