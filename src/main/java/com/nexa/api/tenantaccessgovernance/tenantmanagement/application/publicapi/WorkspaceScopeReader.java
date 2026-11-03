package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.List;
import java.util.UUID;

/** Dedicated metadata-only port for bounded cross-Tenant Workspace enumeration. */
public interface WorkspaceScopeReader {
    List<WorkspaceDirectory.Scope> scanAfter(UUID tenantId, UUID workspaceId, int limit);

    /** Active Workspace rows, preserving legacy semantics without filtering Tenant lifecycle. */
    List<WorkspaceDirectory.Scope> scanActiveWorkspacesAfter(UUID tenantId, UUID workspaceId, int limit);

    List<WorkspaceDirectory.Scope> scanActiveAfter(UUID tenantId, UUID workspaceId, int limit);
}
