package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;

import java.util.Objects;

/** Reads the authoritative workspace policy before a Tenant database callback begins. */
public final class TenantBusinessDatabaseDeliveryLeadTimePolicyResolver {
    private final OperationalSettingsAccess settings;

    public TenantBusinessDatabaseDeliveryLeadTimePolicyResolver(OperationalSettingsAccess settings) {
        this.settings = Objects.requireNonNull(settings, "Central workspace settings are required");
    }

    public int minimumDeliveryLeadDays(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified Tenant access context is required");
        var source = settings.findDeliveryLeadTimePolicy(context.tenantId(), context.workspaceId())
                .orElseThrow(TenantBusinessDatabasePolicySnapshotConflictException::new);
        if (!source.tenantId().equals(context.tenantId()) || !source.workspaceId().equals(context.workspaceId())) {
            throw new TenantBusinessDatabasePolicySnapshotConflictException();
        }
        return source.minimumDeliveryLeadDays();
    }
}
