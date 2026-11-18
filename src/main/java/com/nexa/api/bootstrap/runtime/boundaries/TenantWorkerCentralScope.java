package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.shared.context.RlsRequestScope;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Exact central RLS context for authority preflight of a READY Tenant worker scope. */
public final class TenantWorkerCentralScope {
    private TenantWorkerCentralScope() { }

    public static <T> T call(UUID tenantId, UUID workspaceId, Supplier<T> action) {
        Objects.requireNonNull(tenantId, "Verified worker Tenant is required");
        Objects.requireNonNull(workspaceId, "Verified worker Workspace is required");
        Objects.requireNonNull(action, "Central worker preflight is required");
        try {
            TenantBusinessDatabaseRouter.assertCentralDataSourceAccessAllowed();
        } catch (SQLException routedTransaction) {
            throw new IllegalStateException("Central preflight requires a closed Tenant transaction", routedTransaction);
        }
        RlsRequestScope.Scope previous = RlsRequestScope.current();
        boolean previousScan = RlsRequestScope.crossScopeWorkspaceScanEnabled();
        if (previous != null && (!tenantId.equals(previous.tenantId())
                || !workspaceId.equals(previous.workspaceId()))) {
            throw new IllegalStateException("Central preflight cannot replace a different active scope");
        }
        RlsRequestScope.clear();
        RlsRequestScope.set(tenantId, workspaceId);
        try {
            return action.get();
        } finally {
            RlsRequestScope.clear();
            if (previous != null) RlsRequestScope.set(previous.tenantId(), previous.workspaceId());
            if (previousScan) RlsRequestScope.enableCrossScopeWorkspaceScan();
        }
    }
}
