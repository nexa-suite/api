package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Prevents tenant-scoped business requests from falling back to the central database. */
public final class CentralTenantBusinessDataAccessGuard {
    public void requireTenantBoundRoute(CurrentAccessContext context) {
        if (context == null) {
            throw new TenantBusinessDatabaseUnavailableException("Verified Tenant access context is required");
        }
        throw new TenantBusinessDatabaseUnavailableException(
                "Tenant business data requires its verified bound database route");
    }
}
