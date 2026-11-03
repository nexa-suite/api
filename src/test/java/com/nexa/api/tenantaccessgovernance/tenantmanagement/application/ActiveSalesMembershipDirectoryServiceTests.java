package com.nexa.api.tenantaccessgovernance.tenantmanagement.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.WorkspaceMembershipSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service.ActiveSalesMembershipDirectoryService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveSalesMembershipDirectoryServiceTests {
    @Test
    void acceptsOnlyActiveSalesMembershipInTheRequestedWorkspace() {
        String tenant = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        String membership = UUID.randomUUID().toString();
        OrganizationAdministrationPort authority = mock(OrganizationAdministrationPort.class);
        when(authority.findMembership(tenant, membership)).thenReturn(Optional.of(new WorkspaceMembershipSummary(
                membership, workspace, UUID.randomUUID().toString(), "seller@example.test", "Seller", "INTERNAL",
                "ACTIVE", 1, Set.of("SALES"), Set.of(), Set.of())));

        var result = new ActiveSalesMembershipDirectoryService(authority)
                .findActiveSalesMembership(tenant, workspace, membership);

        assertThat(result).contains(new com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi
                .ActiveSalesMembershipDirectory.SalesMembershipReference(membership));
    }

    @Test
    void rejectsInactiveOrWrongWorkspaceMembershipsEvenWhenTheyCarrySalesRole() {
        String tenant = UUID.randomUUID().toString();
        String requestedWorkspace = UUID.randomUUID().toString();
        String membership = UUID.randomUUID().toString();
        OrganizationAdministrationPort authority = mock(OrganizationAdministrationPort.class);
        when(authority.findMembership(tenant, membership)).thenReturn(Optional.of(new WorkspaceMembershipSummary(
                membership, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "seller@example.test", "Seller",
                "INTERNAL", "ACTIVE", 1, Set.of("SALES"), Set.of(), Set.of())));

        assertThat(new ActiveSalesMembershipDirectoryService(authority)
                .findActiveSalesMembership(tenant, requestedWorkspace, membership)).isEmpty();

        when(authority.findMembership(tenant, membership)).thenReturn(Optional.of(new WorkspaceMembershipSummary(
                membership, requestedWorkspace, UUID.randomUUID().toString(), "seller@example.test", "Seller",
                "INTERNAL", "SUSPENDED", 2, Set.of("SALES"), Set.of(), Set.of())));
        assertThat(new ActiveSalesMembershipDirectoryService(authority)
                .findActiveSalesMembership(tenant, requestedWorkspace, membership)).isEmpty();
    }

    @Test
    void rejectsBuyerWithoutSalesRole() {
        String tenant = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        String membership = UUID.randomUUID().toString();
        OrganizationAdministrationPort authority = mock(OrganizationAdministrationPort.class);
        when(authority.findMembership(tenant, membership)).thenReturn(Optional.of(new WorkspaceMembershipSummary(
                membership, workspace, UUID.randomUUID().toString(), "buyer@example.test", "Buyer", "BUYER",
                "ACTIVE", 1, Set.of("BUYER"), Set.of(), Set.of())));

        assertThat(new ActiveSalesMembershipDirectoryService(authority)
                .findActiveSalesMembership(tenant, workspace, membership)).isEmpty();
    }
}
