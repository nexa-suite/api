package com.nexa.api.tenantmanagement.application.service;

import com.nexa.api.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantmanagement.application.publicapi.BuyerMembershipDirectory;

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
}
