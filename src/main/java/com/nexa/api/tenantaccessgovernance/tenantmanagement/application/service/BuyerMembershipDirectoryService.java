package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;

import java.util.List;

public final class BuyerMembershipDirectoryService implements BuyerMembershipDirectory {
    private final OrganizationAdministrationPort memberships;

    public BuyerMembershipDirectoryService(OrganizationAdministrationPort memberships) {
        this.memberships = memberships;
    }

    @Override
    public List<BuyerMembershipReference> findActiveBuyers(String tenantId, String workspaceId) {
        return memberships.findMemberships(tenantId, workspaceId).stream()
                .filter(value -> "ACTIVE".equals(value.status()))
                .filter(value -> value.roles().stream().anyMatch("BUYER"::equalsIgnoreCase))
                .map(value -> new BuyerMembershipReference(value.id(), value.email(), value.displayName()))
                .toList();
    }

    @Override
    public java.util.Optional<ActiveBuyerIdentityReference> findActiveBuyerIdentity(
            String tenantId, String workspaceId, String membershipId) {
        return memberships.findMembership(tenantId, membershipId)
                .filter(value -> workspaceId.equals(value.workspaceId()))
                .filter(value -> "ACTIVE".equals(value.status()))
                .filter(value -> value.roles().stream().anyMatch("BUYER"::equalsIgnoreCase))
                .map(value -> new ActiveBuyerIdentityReference(value.id(), value.userId()));
    }
}
