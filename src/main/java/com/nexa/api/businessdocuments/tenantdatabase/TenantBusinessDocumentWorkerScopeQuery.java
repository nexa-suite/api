package com.nexa.api.businessdocuments.tenantdatabase;

import java.util.List;
import java.util.UUID;

/** Central, read-only access to READY Tenant bindings with Workspace anchors for BC-09 workers. */
public interface TenantBusinessDocumentWorkerScopeQuery {
    List<Scope> listReadyWorkspaces(UUID afterTenantId, UUID afterWorkspaceId, int limit);

    /** Checks only the supplied pair; it does not enumerate other Tenant scopes. */
    boolean isReadyWorkspace(UUID tenantId, UUID workspaceId);

    record Scope(UUID tenantId, UUID workspaceId) {
        public Scope {
            if (tenantId == null || workspaceId == null) {
                throw new IllegalArgumentException("Tenant and Workspace are required");
            }
        }
    }
}
