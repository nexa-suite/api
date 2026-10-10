package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.List;
import java.util.Optional;

/** Read-only Tenant & Access contract for active Buyer memberships. */
public interface BuyerMembershipDirectory {
    List<BuyerMembershipReference> findActiveBuyers(String tenantId, String workspaceId);

    default Optional<BuyerMembershipReference> findActiveBuyer(
            String tenantId, String workspaceId, String membershipId) {
        return findActiveBuyers(tenantId, workspaceId).stream()
                .filter(candidate -> candidate.id().equals(membershipId))
                .findFirst();
    }

    /** Stable BC-01 identity for an active Buyer membership; exposes no profile data. */
    default Optional<ActiveBuyerIdentityReference> findActiveBuyerIdentity(
            String tenantId, String workspaceId, String membershipId) {
        return Optional.empty();
    }

    record BuyerMembershipReference(String id, String email, String displayName) {
    }

    record ActiveBuyerIdentityReference(String membershipId, String humanIdentityId) {
    }
}
