package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.List;
import java.util.UUID;

/** Governance-owned workspace scope lookup and ordered worker enumeration. */
public interface WorkspaceDirectory {
    boolean exists(UUID tenantId, UUID workspaceId);
    List<Scope> scanAfter(UUID tenantId, UUID workspaceId, int limit);
    /** Same keyset scan, limited to ACTIVE tenant and workspace seed targets. */
    List<Scope> scanActiveAfter(UUID tenantId, UUID workspaceId, int limit);
    record Scope(UUID tenantId, UUID workspaceId) { }
}
