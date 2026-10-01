package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Governance-owned membership and logistics workforce queries for same-process consumers. */
public interface WorkforceDirectory {
    boolean membershipExists(UUID tenantId, UUID workspaceId, UUID membershipId);

    Optional<String> findAssignableLogisticsName(UUID tenantId, UUID workspaceId, UUID membershipId);

    List<LogisticsAssignee> findLogisticsAssignees(UUID tenantId, UUID workspaceId);

    Set<UUID> filterActiveBuyerMembershipIds(UUID tenantId, UUID workspaceId, List<UUID> membershipIds);

    record LogisticsAssignee(UUID id, UUID userId, String email, String displayName) { }
}
