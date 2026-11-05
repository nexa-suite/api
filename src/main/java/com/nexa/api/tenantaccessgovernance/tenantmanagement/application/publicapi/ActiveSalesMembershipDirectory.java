package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.Optional;

/** Server-side authority lookup for active Sales memberships in an exact Tenant/Workspace. */
public interface ActiveSalesMembershipDirectory {
    Optional<SalesMembershipReference> findActiveSalesMembership(
            String tenantId, String workspaceId, String membershipId);

    record SalesMembershipReference(String id) { }
}
