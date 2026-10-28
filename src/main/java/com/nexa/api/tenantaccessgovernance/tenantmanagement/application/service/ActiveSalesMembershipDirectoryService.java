package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;

import java.util.Objects;
import java.util.Optional;

public final class ActiveSalesMembershipDirectoryService implements ActiveSalesMembershipDirectory {
    private final OrganizationAdministrationPort memberships;

    public ActiveSalesMembershipDirectoryService(OrganizationAdministrationPort memberships) {
        this.memberships = Objects.requireNonNull(memberships);
    }

    @Override
    public Optional<SalesMembershipReference> findActiveSalesMembership(
            String tenantId, String workspaceId, String membershipId) {
        return memberships.findMembership(tenantId, membershipId)
                .filter(value -> workspaceId.equals(value.workspaceId()))
                .filter(value -> "ACTIVE".equals(value.status()))
                .filter(value -> value.roles().stream().anyMatch("SALES"::equalsIgnoreCase))
                .map(value -> new SalesMembershipReference(value.id()));
    }
}
