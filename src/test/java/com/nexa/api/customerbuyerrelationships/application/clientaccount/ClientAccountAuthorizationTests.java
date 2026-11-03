package com.nexa.api.customerbuyerrelationships.application.clientaccount;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountSalesOwnerAssignment;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipMutation;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerClientAccountRegistrationCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountRegistrationResult;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.service.ClientAccountService;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.service.ClientAccountAddressService;
import com.nexa.api.customerbuyerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientAccountAuthorizationTests {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final String accountId = UUID.randomUUID().toString();

    @Test
    void salesListAndDetailAreRestrictedToAuthenticatedMembershipOwner() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        var page = new CustomerAccountPage<ClientAccountView>(List.of(), 0, 10, 0);
        ClientAccountView owned = account(accountId, sales.membershipId().toString());
        when(persistence.listForSalesOwner(tenantId.toString(), workspaceId.toString(), sales.membershipId().toString(),
                null, null, 0, 10)).thenReturn(page);
        doReturn(Optional.of(owned)).when(persistence).findOwnedBySalesOwner(tenantId.toString(), workspaceId.toString(), accountId,
                sales.membershipId().toString());
        ClientAccountService service = service(persistence, mock(BuyerMembershipDirectory.class));

        assertThat(service.list(sales, null, null, 0, 10)).isSameAs(page);
        assertThat(service.detail(sales, accountId)).isSameAs(owned);
        verify(persistence).listForSalesOwner(tenantId.toString(), workspaceId.toString(),
                sales.membershipId().toString(), null, null, 0, 10);
        verify(persistence).findOwnedBySalesOwner(tenantId.toString(), workspaceId.toString(), accountId,
                sales.membershipId().toString());
        verify(persistence, never()).list(tenantId.toString(), workspaceId.toString(), null, null, 0, 10);
        verify(persistence, never()).find(tenantId.toString(), workspaceId.toString(), accountId);
    }

    @Test
    void salesCannotReadOrMutateAccountOwnedByAnotherMembership() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        doReturn(Optional.empty()).when(persistence).findOwnedBySalesOwner(tenantId.toString(), workspaceId.toString(), accountId,
                sales.membershipId().toString());
        ClientAccountService service = service(persistence, mock(BuyerMembershipDirectory.class));

        assertThatThrownBy(() -> service.detail(sales, accountId)).isInstanceOf(ApiResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(sales, accountId, account(accountId, null), 0))
                .isInstanceOf(ApiResourceNotFoundException.class);
        assertThatThrownBy(() -> service.changeStatus(sales, accountId, "SUSPENDED", 0))
                .isInstanceOf(ApiResourceNotFoundException.class);
        verify(persistence, never()).update(tenantId.toString(), workspaceId.toString(), accountId,
                "Business", "Commercial", "Contact", "buyer@example.test", "+51999999999",
                "Cold chain", "cash", 0);
        verify(persistence, never()).updateStatus(tenantId.toString(), workspaceId.toString(), accountId,
                "SUSPENDED", 0);
    }

    @Test
    void newlyCreatedSalesAccountStoresAuthenticatedMembershipAsOwner() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        ClientAccountView command = account(null, null);
        ClientAccountView created = account(accountId, sales.membershipId().toString());
        doReturn(Optional.of(created)).when(persistence).findOwnedBySalesOwner(
                org.mockito.ArgumentMatchers.eq(tenantId.toString()), org.mockito.ArgumentMatchers.eq(workspaceId.toString()),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(sales.membershipId().toString()));
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        ClientAccountService service = service(persistence, buyers);

        assertThat(service.create(sales, command)).isSameAs(created);
        verify(persistence).insert(org.mockito.ArgumentMatchers.eq(command),
                org.mockito.ArgumentMatchers.eq(tenantId.toString()), org.mockito.ArgumentMatchers.eq(workspaceId.toString()),
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(sales.membershipId().value()),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void businessOperationsManagerCanReadAllAccountsButCannotMutateThem() {
        CurrentAccessContext bom = context(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        var page = new CustomerAccountPage<ClientAccountView>(List.of(), 0, 10, 0);
        when(persistence.list(tenantId.toString(), workspaceId.toString(), null, null, 0, 10)).thenReturn(page);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        ClientAccountService service = service(persistence, buyers);

        assertThat(service.list(bom, null, null, 0, 10)).isSameAs(page);
        assertThatThrownBy(() -> service.create(bom, null)).isInstanceOf(AccessPolicyViolation.class);
        assertThatThrownBy(() -> service.associateBuyerMembership(bom, accountId, UUID.randomUUID().toString(), 0))
                .isInstanceOf(AccessPolicyViolation.class);
        verify(persistence).list(tenantId.toString(), workspaceId.toString(), null, null, 0, 10);
        verifyNoInteractions(buyers);
    }

    @Test
    void sellerCannotEnumerateUnrelatedBuyerMembershipNamesOrEmails() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        ClientAccountService service = service(persistence, buyers);

        assertThatThrownBy(() -> service.buyerMembershipCandidates(sales)).isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(persistence, buyers);
    }

    @Test
    void businessAccountBuyerMembershipCollectionShowsOnlyLinkedRowsAndCurrentCentralIdentity() {
        CurrentAccessContext owner = context(MembershipRole.COMPANY_OWNER);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        String activeMembershipId = UUID.randomUUID().toString();
        String inactiveMembershipId = UUID.randomUUID().toString();
        ClientAccountView account = account(accountId, activeMembershipId);
        when(persistence.find(tenantId.toString(), workspaceId.toString(), accountId)).thenReturn(Optional.of(account));
        when(persistence.listBuyerMemberships(tenantId.toString(), workspaceId.toString(), accountId, null, 0, 25))
                .thenReturn(new ClientAccountBuyerMembershipPage(List.of(
                        new ClientAccountBuyerMembershipView(activeMembershipId, "ACTIVE", null, null,
                                Instant.EPOCH, Instant.EPOCH),
                        new ClientAccountBuyerMembershipView(inactiveMembershipId, "INACTIVE", null, null,
                                Instant.EPOCH, Instant.EPOCH)), 0, 25, 2, account.version()));
        when(buyers.findActiveBuyers(tenantId.toString(), workspaceId.toString())).thenReturn(List.of(
                new BuyerMembershipDirectory.BuyerMembershipReference(activeMembershipId, "buyer@example.test", "Buyer")));

        ClientAccountBuyerMembershipPage result = service(persistence, buyers).buyerMemberships(owner, accountId, 0, 25);

        assertThat(result.accountVersion()).isEqualTo(account.version());
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(ClientAccountBuyerMembershipView::status)
                .containsExactly("ACTIVE", "INACTIVE");
        assertThat(result.items().getFirst().email()).isEqualTo("buyer@example.test");
        assertThat(result.items().get(1).email()).isNull();
        verify(persistence).listBuyerMemberships(tenantId.toString(), workspaceId.toString(), accountId, null, 0, 25);
    }

    @Test
    void salesBuyerMembershipCollectionIsScopedToAssignedSalesMembership() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        ClientAccountView account = account(accountId, UUID.randomUUID().toString());
        ClientAccountBuyerMembershipPage page = new ClientAccountBuyerMembershipPage(List.of(), 0, 25, 0,
                account.version());
        when(persistence.findOwnedBySalesOwner(tenantId.toString(), workspaceId.toString(), accountId,
                sales.membershipId().toString())).thenReturn(Optional.of(account));
        when(persistence.listBuyerMemberships(tenantId.toString(), workspaceId.toString(), accountId,
                sales.membershipId().toString(), 0, 25)).thenReturn(page);
        when(buyers.findActiveBuyers(tenantId.toString(), workspaceId.toString())).thenReturn(List.of());

        assertThat(service(persistence, buyers).buyerMemberships(sales, accountId, 0, 25).accountVersion())
                .isEqualTo(account.version());
        verify(persistence).listBuyerMemberships(tenantId.toString(), workspaceId.toString(), accountId,
                sales.membershipId().toString(), 0, 25);
    }

    @Test
    void businessAccountCanAddSecondBuyerAndTraceAssociation() {
        CurrentAccessContext owner = context(MembershipRole.COMPANY_OWNER);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        String buyerId = UUID.randomUUID().toString();
        ClientAccountView account = new ClientAccountView(accountId, "C-1", "Business", "Commercial", "PE", "RUC",
                "20123456789", "GENERAL", "Contact", "buyer@example.test", "+51999999999", "Cold chain", "cash",
                "ACTIVE", UUID.randomUUID().toString(), 7, ClientAccountType.BUSINESS);
        when(persistence.find(tenantId.toString(), workspaceId.toString(), accountId)).thenReturn(Optional.of(account));
        when(persistence.findBuyerMembership(tenantId.toString(), workspaceId.toString(), accountId, buyerId, null))
                .thenReturn(Optional.empty());
        when(buyers.findActiveBuyer(tenantId.toString(), workspaceId.toString(), buyerId))
                .thenReturn(Optional.of(new BuyerMembershipDirectory.BuyerMembershipReference(
                        buyerId, "new-buyer@example.test", "New Buyer")));
        when(persistence.isBuyerMembershipAssigned(tenantId.toString(), workspaceId.toString(), buyerId))
                .thenReturn(false);
        when(persistence.associateBuyerMembership(org.mockito.ArgumentMatchers.eq(tenantId.toString()),
                org.mockito.ArgumentMatchers.eq(workspaceId.toString()), org.mockito.ArgumentMatchers.eq(accountId),
                org.mockito.ArgumentMatchers.eq(buyerId), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(7L))).thenReturn(1);

        ClientAccountBuyerMembershipMutation result = service(persistence, buyers,
                mock(ActiveSalesMembershipDirectory.class), traceability)
                .associateBuyerMembership(owner, accountId, buyerId, 7);

        assertThat(result.created()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(result.accountVersion()).isEqualTo(8);
        assertThat(result.membership().status()).isEqualTo("ACTIVE");
        assertThat(result.membership().displayName()).isEqualTo("New Buyer");
        org.mockito.ArgumentCaptor<BusinessTraceabilityCommands.TraceRequest> event =
                org.mockito.ArgumentCaptor.forClass(BusinessTraceabilityCommands.TraceRequest.class);
        verify(traceability).record(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("CLIENT_ACCOUNT_BUYER_MEMBERSHIP_ASSOCIATED");
        assertThat(event.getValue().metadata()).containsEntry("clientAccountId", accountId)
                .containsEntry("buyerMembershipId", buyerId).containsEntry("status", "ACTIVE");
    }

    @Test
    void individualAccountCannotAssociateASecondBuyer() {
        CurrentAccessContext owner = context(MembershipRole.COMPANY_OWNER);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        String buyerId = UUID.randomUUID().toString();
        ClientAccountView individual = new ClientAccountView(accountId, "BUY-1", "Person", "Person", null,
                null, null, "UNASSIGNED", "Person", "buyer@example.test", "+51999999999", "", "CASH",
                "ACTIVE", UUID.randomUUID().toString(), 4, ClientAccountType.INDIVIDUAL);
        when(persistence.find(tenantId.toString(), workspaceId.toString(), accountId)).thenReturn(Optional.of(individual));
        when(persistence.findBuyerMembership(tenantId.toString(), workspaceId.toString(), accountId, buyerId, null))
                .thenReturn(Optional.empty());
        when(buyers.findActiveBuyer(tenantId.toString(), workspaceId.toString(), buyerId))
                .thenReturn(Optional.of(new BuyerMembershipDirectory.BuyerMembershipReference(
                        buyerId, "other@example.test", "Other Buyer")));
        when(persistence.isBuyerMembershipAssigned(tenantId.toString(), workspaceId.toString(), buyerId))
                .thenReturn(false);
        when(persistence.listBuyerMemberships(tenantId.toString(), workspaceId.toString(), accountId, null, 0, 1))
                .thenReturn(new ClientAccountBuyerMembershipPage(List.of(), 0, 1, 1, individual.version()));

        assertThatThrownBy(() -> service(persistence, buyers).associateBuyerMembership(owner, accountId, buyerId, 4))
                .isInstanceOf(CustomerRelationshipConflictException.class);
        verify(persistence, never()).associateBuyerMembership(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void deactivatingBuyerMembershipKeepsHistoryAndIncrementsAccountVersion() {
        CurrentAccessContext owner = context(MembershipRole.COMPANY_OWNER);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        String buyerId = UUID.randomUUID().toString();
        ClientAccountView account = account(accountId, buyerId);
        ClientAccountBuyerMembershipView linked = new ClientAccountBuyerMembershipView(
                buyerId, "ACTIVE", "buyer@example.test", "Buyer", Instant.EPOCH, Instant.EPOCH);
        when(persistence.find(tenantId.toString(), workspaceId.toString(), accountId)).thenReturn(Optional.of(account));
        when(persistence.findBuyerMembership(tenantId.toString(), workspaceId.toString(), accountId, buyerId, null))
                .thenReturn(Optional.of(linked));
        when(persistence.changeBuyerMembershipStatus(org.mockito.ArgumentMatchers.eq(tenantId.toString()),
                org.mockito.ArgumentMatchers.eq(workspaceId.toString()), org.mockito.ArgumentMatchers.eq(accountId),
                org.mockito.ArgumentMatchers.eq(buyerId), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("ACTIVE"), org.mockito.ArgumentMatchers.eq("INACTIVE"),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(account.version()))).thenReturn(1);

        ClientAccountBuyerMembershipMutation result = service(persistence, buyers,
                mock(ActiveSalesMembershipDirectory.class), traceability)
                .deactivateBuyerMembership(owner, accountId, buyerId, account.version());

        assertThat(result.changed()).isTrue();
        assertThat(result.membership().status()).isEqualTo("INACTIVE");
        assertThat(result.membership().createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(result.accountVersion()).isEqualTo(account.version() + 1);
        verify(traceability).record(org.mockito.ArgumentMatchers.argThat(event ->
                event.eventType().equals("CLIENT_ACCOUNT_BUYER_MEMBERSHIP_DEACTIVATED")));
    }

    @Test
    void salesAddressReadsRequireMatchingSalesOwnerMembership() {
        CurrentAccessContext sales = context(MembershipRole.SALES);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        ClientAccountAddressPersistencePort addressesPersistence = mock(ClientAccountAddressPersistencePort.class);
        when(accounts.findSalesOwnerReference(tenantId.toString(), workspaceId.toString(), accountId,
                sales.membershipId().toString())).thenReturn(Optional.empty());
        ClientAccountAddressService addresses = new ClientAccountAddressService(addressesPersistence, accounts);

        assertThatThrownBy(() -> addresses.list(sales, accountId)).isInstanceOf(ApiResourceNotFoundException.class);
        verify(accounts).findSalesOwnerReference(tenantId.toString(), workspaceId.toString(), accountId,
                sales.membershipId().toString());
        verify(accounts, never()).findReference(tenantId.toString(), workspaceId.toString(), accountId);
        verifyNoInteractions(addressesPersistence);
    }

    @Test
    void buyerRegistrationCreatesOnlyAuthenticatedMembershipRelationshipAndExactRetryIsSafe() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        String membershipId = buyer.membershipId().toString();
        BuyerClientAccountRegistrationCommand command = businessRegistration();
        ClientAccountView expected = registeredBusiness(membershipId);
        ClientAccountView created = new ClientAccountView(accountId, expected.code(), expected.businessName(),
                expected.commercialName(), expected.countryCode(), expected.taxType(), expected.taxValue(),
                expected.segment(), expected.contactPerson(), expected.contactEmail(), expected.phone(),
                expected.deliveryProfile(), expected.paymentCondition(), "ACTIVE", membershipId, 1, expected.accountType());
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        doReturn(Optional.of(new BuyerMembershipDirectory.BuyerMembershipReference(
                membershipId, "buyer@example.test", "Buyer"))).when(buyers)
                .findActiveBuyer(tenantId.toString(), workspaceId.toString(), membershipId);
        when(persistence.isBuyerMembershipAssigned(tenantId.toString(), workspaceId.toString(), membershipId))
                .thenReturn(false, true);
        doReturn(1).when(persistence).associateBuyer(org.mockito.ArgumentMatchers.eq(tenantId.toString()),
                org.mockito.ArgumentMatchers.eq(workspaceId.toString()), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(membershipId), org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(0L));
        doReturn(Optional.of(created)).when(persistence).findForBuyer(tenantId.toString(), workspaceId.toString(), membershipId);
        when(persistence.isExactBuyerRegistration(tenantId.toString(), workspaceId.toString(), membershipId, expected))
                .thenReturn(true);
        doReturn(Optional.of(created)).when(persistence)
                .findAnyForBuyer(tenantId.toString(), workspaceId.toString(), membershipId);
        ClientAccountService service = service(persistence, buyers);

        ClientAccountRegistrationResult first = service.registerOwnBusiness(buyer, command);
        ClientAccountRegistrationResult retry = service.registerOwnBusiness(buyer, command);

        assertThat(first.created()).isTrue();
        assertThat(first.account()).isSameAs(created);
        assertThat(retry.created()).isFalse();
        assertThat(retry.account().id()).isEqualTo(accountId);
        verify(persistence).insert(org.mockito.ArgumentMatchers.eq(expected),
                org.mockito.ArgumentMatchers.eq(tenantId.toString()), org.mockito.ArgumentMatchers.eq(workspaceId.toString()),
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(persistence).isExactBuyerRegistration(tenantId.toString(), workspaceId.toString(), membershipId,
                expected);
        verify(persistence, org.mockito.Mockito.times(1)).associateBuyer(
                org.mockito.ArgumentMatchers.eq(tenantId.toString()), org.mockito.ArgumentMatchers.eq(workspaceId.toString()),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(membershipId),
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(0L));
    }

    @Test
    void buyerCannotReadAnotherBuyerAccountThroughAccountIdRoute() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        assertThatThrownBy(() -> service(persistence, mock(BuyerMembershipDirectory.class))
                .detail(buyer, accountId)).isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(persistence);
    }

    @Test
    void individualBuyerRegistrationUsesServerIdentityAndDoesNotPersistBusinessTaxData() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        String membershipId = buyer.membershipId().toString();
        BuyerClientAccountRegistrationCommand request = new BuyerClientAccountRegistrationCommand(
                ClientAccountType.INDIVIDUAL, null, null, null, null, null, "+51999999999", null);
        ClientAccountView expected = new ClientAccountView(null,
                "BUY-" + membershipId.replace("-", "").substring(0, 24).toUpperCase(java.util.Locale.ROOT),
                "Buyer", "Buyer", null, null, null,
                "UNASSIGNED", "Buyer", "buyer@example.test", "+51999999999", "", "CASH", "ACTIVE",
                membershipId, 0, ClientAccountType.INDIVIDUAL);
        ClientAccountView stored = new ClientAccountView(accountId, expected.code(), expected.businessName(),
                expected.commercialName(), null, null, null, expected.segment(), expected.contactPerson(),
                expected.contactEmail(), expected.phone(), expected.deliveryProfile(), expected.paymentCondition(),
                "ACTIVE", membershipId, 1, ClientAccountType.INDIVIDUAL);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        doReturn(Optional.of(new BuyerMembershipDirectory.BuyerMembershipReference(
                membershipId, "buyer@example.test", "Buyer"))).when(buyers)
                .findActiveBuyer(tenantId.toString(), workspaceId.toString(), membershipId);
        doReturn(1).when(persistence).associateBuyer(org.mockito.ArgumentMatchers.eq(tenantId.toString()),
                org.mockito.ArgumentMatchers.eq(workspaceId.toString()), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(membershipId), org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(0L));
        doReturn(Optional.of(stored)).when(persistence).findForBuyer(tenantId.toString(), workspaceId.toString(), membershipId);

        ClientAccountRegistrationResult result = service(persistence, buyers).registerOwnBusiness(buyer, request);

        assertThat(result.created()).isTrue();
        assertThat(result.account().accountType()).isEqualTo(ClientAccountType.INDIVIDUAL);
        assertThat(result.account().businessName()).isEqualTo("Buyer");
        assertThat(result.account().contactEmail()).isEqualTo("buyer@example.test");
        assertThat(result.account().taxValue()).isNull();
        verify(persistence).insert(org.mockito.ArgumentMatchers.eq(expected),
                org.mockito.ArgumentMatchers.eq(tenantId.toString()), org.mockito.ArgumentMatchers.eq(workspaceId.toString()),
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void buyerMembershipCannotBeVerifiedOnPlatformSurface() {
        assertThatThrownBy(() -> context(MembershipRole.BUYER, Surface.PLATFORM))
                .isInstanceOf(AccessPolicyViolation.class);
    }

    @Test
    void buyerCannotRegisterWhenMembershipAlreadyOwnsDifferentAccount() {
        CurrentAccessContext buyer = context(MembershipRole.BUYER);
        String membershipId = buyer.membershipId().toString();
        BuyerClientAccountRegistrationCommand request = businessRegistration();
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
        doReturn(Optional.of(new BuyerMembershipDirectory.BuyerMembershipReference(
                membershipId, "buyer@example.test", "Buyer"))).when(buyers)
                .findActiveBuyer(tenantId.toString(), workspaceId.toString(), membershipId);
        when(persistence.isBuyerMembershipAssigned(tenantId.toString(), workspaceId.toString(), membershipId))
                .thenReturn(true);
        doReturn(Optional.of(account(accountId, membershipId))).when(persistence)
                .findAnyForBuyer(tenantId.toString(), workspaceId.toString(), membershipId);

        assertThatThrownBy(() -> service(persistence, buyers).registerOwnBusiness(buyer, request))
                .isInstanceOf(CustomerRelationshipConflictException.class);
        verify(persistence, never()).insert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void bomCanAssignBuyerAccountToActiveSalesMembershipAndRecordsBusinessTrace() {
        CurrentAccessContext bom = context(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        String salesMembershipId = UUID.randomUUID().toString();
        ClientAccountView unassigned = new ClientAccountView(accountId, "BUY-1", "Buyer", "Buyer", null,
                null, null, "UNASSIGNED", "Buyer", "buyer@example.test", "+51999999999", "", "CASH",
                "ACTIVE", UUID.randomUUID().toString(), 4, ClientAccountType.INDIVIDUAL, null);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        ActiveSalesMembershipDirectory sellers = mock(ActiveSalesMembershipDirectory.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        doReturn(Optional.of(new ActiveSalesMembershipDirectory.SalesMembershipReference(salesMembershipId)))
                .when(sellers).findActiveSalesMembership(tenantId.toString(), workspaceId.toString(), salesMembershipId);
        doReturn(Optional.of(unassigned)).when(persistence).find(tenantId.toString(), workspaceId.toString(), accountId);
        when(persistence.updateSalesOwner(tenantId.toString(), workspaceId.toString(), accountId,
                UUID.fromString(salesMembershipId), 4)).thenReturn(1);

        ClientAccountSalesOwnerAssignment result = service(persistence, mock(BuyerMembershipDirectory.class),
                sellers, traceability).assignSalesOwner(bom, accountId, salesMembershipId, 4);

        assertThat(result).isEqualTo(new ClientAccountSalesOwnerAssignment(accountId, salesMembershipId, 5));
        org.mockito.ArgumentCaptor<BusinessTraceabilityCommands.TraceRequest> event =
                org.mockito.ArgumentCaptor.forClass(BusinessTraceabilityCommands.TraceRequest.class);
        verify(traceability).record(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("CLIENT_ACCOUNT_SALES_OWNER_ASSIGNED");
        assertThat(event.getValue().actorMembershipId()).isEqualTo(bom.membershipId().value());
        assertThat(event.getValue().metadata()).containsEntry("salesOwnerMembershipId", salesMembershipId);
        verify(persistence).updateSalesOwner(tenantId.toString(), workspaceId.toString(), accountId,
                UUID.fromString(salesMembershipId), 4);
    }

    @Test
    void companyOwnerCanTransferAssignedAccountOnlyToActiveSameScopeSalesMembership() {
        CurrentAccessContext owner = context(MembershipRole.COMPANY_OWNER);
        String previousSalesId = UUID.randomUUID().toString();
        String targetSalesId = UUID.randomUUID().toString();
        ClientAccountView assigned = new ClientAccountView(accountId, "BUY-1", "Buyer", "Buyer", null,
                null, null, "UNASSIGNED", "Buyer", "buyer@example.test", "+51999999999", "", "CASH",
                "ACTIVE", UUID.randomUUID().toString(), 2, ClientAccountType.BUSINESS, previousSalesId);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        ActiveSalesMembershipDirectory sellers = mock(ActiveSalesMembershipDirectory.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        doReturn(Optional.of(new ActiveSalesMembershipDirectory.SalesMembershipReference(targetSalesId)))
                .when(sellers).findActiveSalesMembership(tenantId.toString(), workspaceId.toString(), targetSalesId);
        doReturn(Optional.of(assigned)).when(persistence).find(tenantId.toString(), workspaceId.toString(), accountId);
        when(persistence.updateSalesOwner(tenantId.toString(), workspaceId.toString(), accountId,
                UUID.fromString(targetSalesId), 2)).thenReturn(1);

        ClientAccountSalesOwnerAssignment result = service(persistence, mock(BuyerMembershipDirectory.class),
                sellers, traceability).assignSalesOwner(owner, accountId, targetSalesId, 2);

        assertThat(result.accountVersion()).isEqualTo(3);
        verify(traceability).record(org.mockito.ArgumentMatchers.argThat(event ->
                event.eventType().equals("CLIENT_ACCOUNT_SALES_OWNER_TRANSFERRED")
                        && event.metadata().get("previousSalesOwnerMembershipId").equals(previousSalesId)
                        && event.metadata().get("salesOwnerMembershipId").equals(targetSalesId)));
    }

    @Test
    void inactiveOrOutOfScopeSalesMembershipCannotBeAssigned() {
        CurrentAccessContext bom = context(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        ActiveSalesMembershipDirectory sellers = mock(ActiveSalesMembershipDirectory.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        String salesId = UUID.randomUUID().toString();
        doReturn(Optional.empty()).when(sellers)
                .findActiveSalesMembership(tenantId.toString(), workspaceId.toString(), salesId);

        assertThatThrownBy(() -> service(persistence, mock(BuyerMembershipDirectory.class), sellers, traceability)
                .assignSalesOwner(bom, accountId, salesId, 0))
                .isInstanceOf(ApiResourceNotFoundException.class);
        verifyNoInteractions(persistence, traceability);
    }

    @Test
    void sellerAndTenantAdminCannotAssignSalesOwner() {
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        ActiveSalesMembershipDirectory sellers = mock(ActiveSalesMembershipDirectory.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        ClientAccountService service = service(persistence, mock(BuyerMembershipDirectory.class), sellers, traceability);

        assertThatThrownBy(() -> service.assignSalesOwner(context(MembershipRole.SALES), accountId,
                UUID.randomUUID().toString(), 0)).isInstanceOf(AccessPolicyViolation.class);
        assertThatThrownBy(() -> service.assignSalesOwner(context(MembershipRole.TENANT_ADMIN), accountId,
                UUID.randomUUID().toString(), 0)).isInstanceOf(AccessPolicyViolation.class);
        verifyNoInteractions(persistence, sellers, traceability);
    }

    @Test
    void repeatedSameSalesOwnerAssignmentReturnsCurrentVersionWithoutDuplicateAudit() {
        CurrentAccessContext bom = context(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        String salesMembershipId = UUID.randomUUID().toString();
        ClientAccountView alreadyAssigned = new ClientAccountView(accountId, "BUY-1", "Buyer", "Buyer", null,
                null, null, "UNASSIGNED", "Buyer", "buyer@example.test", "+51999999999", "", "CASH",
                "ACTIVE", UUID.randomUUID().toString(), 5, ClientAccountType.BUSINESS, salesMembershipId);
        ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
        ActiveSalesMembershipDirectory sellers = mock(ActiveSalesMembershipDirectory.class);
        BusinessTraceabilityCommands traceability = mock(BusinessTraceabilityCommands.class);
        doReturn(Optional.of(new ActiveSalesMembershipDirectory.SalesMembershipReference(salesMembershipId)))
                .when(sellers).findActiveSalesMembership(tenantId.toString(), workspaceId.toString(), salesMembershipId);
        doReturn(Optional.of(alreadyAssigned)).when(persistence).find(tenantId.toString(), workspaceId.toString(), accountId);

        assertThat(service(persistence, mock(BuyerMembershipDirectory.class), sellers, traceability)
                .assignSalesOwner(bom, accountId, salesMembershipId, 2))
                .isEqualTo(new ClientAccountSalesOwnerAssignment(accountId, salesMembershipId, 5));
        verify(persistence, never()).updateSalesOwner(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(traceability);
    }

    private ClientAccountService service(ClientAccountPersistencePort persistence, BuyerMembershipDirectory buyers) {
        return new ClientAccountService(persistence, buyers);
    }

    private ClientAccountService service(ClientAccountPersistencePort persistence, BuyerMembershipDirectory buyers,
            ActiveSalesMembershipDirectory sellers, BusinessTraceabilityCommands traceability) {
        return new ClientAccountService(persistence, buyers, sellers, traceability);
    }

    private ClientAccountView account(String id, String buyerMembershipId) {
        return new ClientAccountView(id, "C-1", "Business", "Commercial", "PE", "RUC", "20123456789",
                "GENERAL", "Contact", "buyer@example.test", "+51999999999", "Cold chain", "cash", "ACTIVE",
                buyerMembershipId, 0, ClientAccountType.BUSINESS);
    }

    private BuyerClientAccountRegistrationCommand businessRegistration() {
        return new BuyerClientAccountRegistrationCommand(ClientAccountType.BUSINESS, "Buyer Business", "Buyer Commercial",
                "PE", "RUC", "20123456789", "+51999999999", "Cold chain");
    }

    private ClientAccountView registeredBusiness(String membershipId) {
        return new ClientAccountView(null,
                "BUY-" + membershipId.replace("-", "").substring(0, 24).toUpperCase(java.util.Locale.ROOT),
                "Buyer Business", "Buyer Commercial", "PE", "RUC", "20123456789", "UNASSIGNED", "Buyer",
                "buyer@example.test", "+51999999999", "Cold chain", "CASH", "ACTIVE", membershipId, 0,
                ClientAccountType.BUSINESS);
    }

    private CurrentAccessContext context(MembershipRole role) {
        RoleDefinition definition = role == MembershipRole.TENANT_ADMIN || role == MembershipRole.COMPANY_OWNER
                ? RoleDefinition.systemReserved(role, Instant.EPOCH)
                : RoleDefinition.systemTemplate(role, Instant.EPOCH);
        EffectiveAuthorization authorization = EffectiveAuthorization.of(List.of(definition), Set.of(), 7);
        Membership membership = new Membership(MembershipId.random(), UserId.random(), new TenantId(tenantId),
                new WorkspaceId(workspaceId), Set.of(role), authorization.roleCodes(), authorization.roleDefinitionIds(),
                MembershipStatus.ACTIVE, authorization.authorizationVersion());
        Surface surface = role == MembershipRole.BUYER ? Surface.PORTAL : Surface.PLATFORM;
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE, authorization), surface);
    }

    private CurrentAccessContext context(MembershipRole role, Surface surface) {
        RoleDefinition definition = role == MembershipRole.TENANT_ADMIN || role == MembershipRole.COMPANY_OWNER
                ? RoleDefinition.systemReserved(role, Instant.EPOCH)
                : RoleDefinition.systemTemplate(role, Instant.EPOCH);
        EffectiveAuthorization authorization = EffectiveAuthorization.of(List.of(definition), Set.of(), 7);
        Membership membership = new Membership(MembershipId.random(), UserId.random(), new TenantId(tenantId),
                new WorkspaceId(workspaceId), Set.of(role), authorization.roleCodes(), authorization.roleDefinitionIds(),
                MembershipStatus.ACTIVE, authorization.authorizationVersion());
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE, authorization), surface);
    }
}
