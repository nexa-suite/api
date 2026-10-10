package com.nexa.api.notifications.application.publicapi;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Central membership/role preflight carried into one exact Tenant/Workspace projection. */
public record PreflightedNotificationRecipients(UUID tenantId, UUID workspaceId, Set<UUID> membershipIds) {
    public PreflightedNotificationRecipients {
        Objects.requireNonNull(tenantId, "Preflighted Tenant id is required");
        Objects.requireNonNull(workspaceId, "Preflighted Workspace id is required");
        membershipIds = Set.copyOf(Objects.requireNonNull(membershipIds, "Preflighted memberships are required"));
    }

    public boolean allows(UUID tenant, UUID workspace, UUID membership) {
        return tenantId.equals(tenant) && workspaceId.equals(workspace) && membershipIds.contains(membership);
    }
}
