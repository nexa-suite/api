package com.nexa.api.customerbuyerrelationships.application.buyeraddress;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.BuyerAddressView;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.service.BuyerPersonalAddressService;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.customerbuyerrelationships.domain.model.buyeraddress.BuyerAddress;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuyerPersonalAddressServiceTests {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();

    @Test
    void buyerListsOnlyItsOwnMembershipAddresses() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        UUID membershipId = buyer.membershipId().value();
        var fixture = fixture();
        when(fixture.accounts.findBuyerReference(TENANT_ID.toString(), WORKSPACE_ID.toString(), membershipId.toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(UUID.randomUUID().toString(), "ACTIVE")));
        when(fixture.persistence.list(TENANT_ID.toString(), WORKSPACE_ID.toString(), membershipId.toString()))
                .thenReturn(List.of(address(membershipId, UUID.randomUUID(), "Home")));

        List<BuyerAddressView> result = fixture.service.listMine(buyer);

        assertThat(result).hasSize(1).extracting(BuyerAddressView::label).containsExactly("Home");
        verify(fixture.persistence).installMembershipScope(membershipId.toString());
        verify(fixture.persistence).list(TENANT_ID.toString(), WORKSPACE_ID.toString(), membershipId.toString());
    }

    @Test
    void salesCannotUseBuyerSelfService() {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.service.listMine(context(MembershipRole.SALES)))
                .isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(fixture.persistence, fixture.accounts);
    }

    @Test
    void salesReadsOnlyBuyerLinkedToItsAssignedAccount() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        UUID accountId = UUID.randomUUID();
        UUID buyerMembershipId = UUID.randomUUID();
        var fixture = fixture();
        when(fixture.accounts.findSalesOwnerReference(TENANT_ID.toString(), WORKSPACE_ID.toString(),
                accountId.toString(), sales.membershipId().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(accountId.toString(), "ACTIVE")));
        when(fixture.accounts.findBuyerReference(TENANT_ID.toString(), WORKSPACE_ID.toString(), buyerMembershipId.toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(accountId.toString(), "ACTIVE")));
        when(fixture.persistence.list(TENANT_ID.toString(), WORKSPACE_ID.toString(), buyerMembershipId.toString()))
                .thenReturn(List.of(address(buyerMembershipId, UUID.randomUUID(), "Receiving")));

        var result = fixture.service.listForAccountBuyer(sales, accountId.toString(), buyerMembershipId);

        assertThat(result).extracting(BuyerAddressView::label).containsExactly("Receiving");
        verify(fixture.persistence).installMembershipScope(buyerMembershipId.toString());
        verify(fixture.persistence).list(TENANT_ID.toString(), WORKSPACE_ID.toString(), buyerMembershipId.toString());
    }

    @Test
    void unassignedSalesCannotReadAnotherAccountsBuyerAddress() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        var fixture = fixture();
        when(fixture.accounts.findSalesOwnerReference(TENANT_ID.toString(), WORKSPACE_ID.toString(),
                "account-id", sales.membershipId().toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.listForAccountBuyer(sales, "account-id", UUID.randomUUID()))
                .isInstanceOf(ApiResourceNotFoundException.class);
        verify(fixture.persistence, never()).installMembershipScope(org.mockito.ArgumentMatchers.anyString());
        verify(fixture.persistence, never()).list(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void bomCanReadRelatedBuyerAddressButCompanyOwnerCannotUseAuditRoute() {
        CurrentAccessContext bom = context(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        UUID accountId = UUID.randomUUID();
        UUID buyerMembershipId = UUID.randomUUID();
        var fixture = fixture();
        when(fixture.accounts.findReference(TENANT_ID.toString(), WORKSPACE_ID.toString(), accountId.toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(accountId.toString(), "ACTIVE")));
        when(fixture.accounts.findBuyerReference(TENANT_ID.toString(), WORKSPACE_ID.toString(), buyerMembershipId.toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(accountId.toString(), "ACTIVE")));
        when(fixture.persistence.list(TENANT_ID.toString(), WORKSPACE_ID.toString(), buyerMembershipId.toString()))
                .thenReturn(List.of());
        assertThat(fixture.service.listForAccountBuyer(bom, accountId.toString(), buyerMembershipId)).isEmpty();

        CurrentAccessContext owner = context(MembershipRole.COMPANY_OWNER);
        assertThatThrownBy(() -> fixture.service.listForAccountBuyer(owner, accountId.toString(), buyerMembershipId))
                .isInstanceOf(AccessPolicyViolation.class);
    }

    @Test
    void accountRouteHidesBuyerMembershipFromOtherClientAccount() {
        CurrentAccessContext bom = context(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        UUID accountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        UUID buyerMembershipId = UUID.randomUUID();
        var fixture = fixture();
        when(fixture.accounts.findReference(TENANT_ID.toString(), WORKSPACE_ID.toString(), accountId.toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(accountId.toString(), "ACTIVE")));
        when(fixture.accounts.findBuyerReference(TENANT_ID.toString(), WORKSPACE_ID.toString(), buyerMembershipId.toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(otherAccountId.toString(), "ACTIVE")));

        assertThatThrownBy(() -> fixture.service.listForAccountBuyer(bom, accountId.toString(), buyerMembershipId))
                .isInstanceOf(ApiResourceNotFoundException.class);
        verify(fixture.persistence, never()).installMembershipScope(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void buyerCannotUpdateAddressOwnedByDifferentMembership() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        UUID addressId = UUID.randomUUID();
        var fixture = fixture();
        when(fixture.accounts.findBuyerReference(TENANT_ID.toString(), WORKSPACE_ID.toString(),
                buyer.membershipId().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(UUID.randomUUID().toString(), "ACTIVE")));
        when(fixture.persistence.find(TENANT_ID.toString(), WORKSPACE_ID.toString(),
                buyer.membershipId().toString(), addressId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.updateMine(buyer, addressId,
                new com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.UpdateBuyerAddressCommand(
                        "Work", Address.peru("STREET", "Main 1", null, "15", "1501", "150101")), 0))
                .isInstanceOf(ApiResourceNotFoundException.class);
        verify(fixture.persistence, never()).update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void buyerCannotDeactivateAddressOwnedByDifferentMembership() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        UUID addressId = UUID.randomUUID();
        var fixture = fixture();
        when(fixture.accounts.findBuyerReference(TENANT_ID.toString(), WORKSPACE_ID.toString(),
                buyer.membershipId().toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(UUID.randomUUID().toString(), "ACTIVE")));
        when(fixture.persistence.find(TENANT_ID.toString(), WORKSPACE_ID.toString(),
                buyer.membershipId().toString(), addressId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.deactivateMine(buyer, addressId, 0))
                .isInstanceOf(ApiResourceNotFoundException.class);
        verify(fixture.persistence, never()).deactivate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static Fixture fixture() {
        BuyerAddressPersistencePort persistence = mock(BuyerAddressPersistencePort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        return new Fixture(persistence, accounts, new BuyerPersonalAddressService(persistence, accounts));
    }

    private static BuyerAddress address(UUID membership, UUID id, String label) {
        return BuyerAddress.rehydrate(id, TENANT_ID, WORKSPACE_ID, membership, label,
                Address.peru("STREET", "Main 1", null, "15", "1501", "150101"), false, true, 0);
    }

    private static CurrentAccessContext context(MembershipRole role) {
        RoleDefinition definition = role == MembershipRole.TENANT_ADMIN || role == MembershipRole.COMPANY_OWNER
                ? RoleDefinition.systemReserved(role, Instant.EPOCH)
                : RoleDefinition.systemTemplate(role, Instant.EPOCH);
        EffectiveAuthorization authorization = EffectiveAuthorization.of(List.of(definition), Set.of(), 7);
        Membership membership = new Membership(MembershipId.random(), UserId.random(), new TenantId(TENANT_ID),
                new WorkspaceId(WORKSPACE_ID), Set.of(role), authorization.roleCodes(), authorization.roleDefinitionIds(),
                MembershipStatus.ACTIVE, authorization.authorizationVersion());
        Surface surface = role == MembershipRole.BUYER ? Surface.PORTAL : Surface.PLATFORM;
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE, authorization), surface);
    }

    private record Fixture(BuyerAddressPersistencePort persistence, CustomerAccountQuery accounts,
                           BuyerPersonalAddressService service) { }
}
