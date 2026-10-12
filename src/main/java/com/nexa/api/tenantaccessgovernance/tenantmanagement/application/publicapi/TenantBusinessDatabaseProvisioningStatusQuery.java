package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.List;
import java.util.UUID;

/** Read-only, non-sensitive Tenant database provisioning status for authorized operator tooling. */
public interface TenantBusinessDatabaseProvisioningStatusQuery {
    List<TenantBusinessDatabaseProvisioningStatus> listRecent(UUID internalOperatorId, int limit);
}
