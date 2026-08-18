package com.nexa.api.iam.application.port.out;

import java.util.Set;
import java.util.UUID;

/** Explicit membership-role assignment intent used by onboarding. */
public interface MembershipRolePersistencePort {
    void assignFounderRoles(UUID membershipId, UUID tenantId, UUID workspaceId, Set<String> roles);
}
