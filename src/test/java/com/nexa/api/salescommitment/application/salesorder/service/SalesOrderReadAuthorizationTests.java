package com.nexa.api.salescommitment.application.salesorder.service;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderSalesOwnerScope;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SalesOrderReadAuthorizationTests {
	private static final SalesOrderFilter FILTER = new SalesOrderFilter(null, 0, 10, "createdAt,desc");
	private final UUID tenantId = UUID.randomUUID();
	private final UUID workspaceId = UUID.randomUUID();
	private final UUID membershipId = UUID.randomUUID();
	private final UUID buyerAccountId = UUID.randomUUID();

	@Test
	void buyerOrderReadGrantIsRequiredBeforeReadingAnySalesOrderProjection() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		SalesOrderPersistencePort persistence = mock(SalesOrderPersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		when(context.hasRole(MembershipRole.BUYER)).thenReturn(true);
		when(context.tenantId()).thenReturn(new TenantId(tenantId));
		when(context.workspaceId()).thenReturn(new WorkspaceId(workspaceId));
		doThrow(new AccessPolicyViolation("Membership role does not have the requested permission"))
				.when(context).requirePermission(PermissionKey.BUYER_ORDER_READ);
		SalesOrderService service = service(persistence, accounts);

		assertThatThrownBy(() -> service.list(context, FILTER)).isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> service.detail(context, "order-id")).isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> service.events(context, "order-id")).isInstanceOf(AccessPolicyViolation.class);

		verify(context, org.mockito.Mockito.times(3)).requirePermission(PermissionKey.BUYER_ORDER_READ);
		verifyNoInteractions(persistence, accounts);
	}

	@Test
	void authorizedBuyerOrderReadRemainsScopedToItsActiveClientAccount() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		SalesOrderPersistencePort persistence = mock(SalesOrderPersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		when(context.hasRole(MembershipRole.BUYER)).thenReturn(true);
		when(context.tenantId()).thenReturn(new TenantId(tenantId));
		when(context.workspaceId()).thenReturn(new WorkspaceId(workspaceId));
		when(context.membershipId()).thenReturn(new MembershipId(membershipId));
		when(accounts.findBuyerReference(tenantId.toString(), workspaceId.toString(), membershipId.toString()))
				.thenReturn(Optional.of(new CustomerAccountReference(buyerAccountId.toString(), "ACTIVE")));
		SalesPage<com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView> expected =
				new SalesPage<>(List.of(), 0, 10, 0);
		when(persistence.list(tenantId.toString(), workspaceId.toString(), buyerAccountId.toString(), FILTER))
				.thenReturn(expected);
		SalesOrderService service = service(persistence, accounts);

		assertThat(service.list(context, FILTER)).isSameAs(expected);

		verify(context).requirePermission(PermissionKey.BUYER_ORDER_READ);
		verify(persistence).list(tenantId.toString(), workspaceId.toString(), buyerAccountId.toString(), FILTER);
	}

	@Test
	void workforceSalesOrderReadUsesTheTypedSalesOrderPermission() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		SalesOrderPersistencePort persistence = mock(SalesOrderPersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		when(context.hasRole(MembershipRole.BUYER)).thenReturn(false);
		when(context.tenantId()).thenReturn(new TenantId(tenantId));
		when(context.workspaceId()).thenReturn(new WorkspaceId(workspaceId));
		SalesPage<com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView> expected =
				new SalesPage<>(List.of(), 0, 10, 0);
		when(persistence.list(tenantId.toString(), workspaceId.toString(), null, FILTER)).thenReturn(expected);
		SalesOrderService service = service(persistence, accounts);

		assertThat(service.list(context, FILTER)).isSameAs(expected);

		verify(context).requirePermission(PermissionKey.SALES_ORDER_READ);
		verify(persistence).list(tenantId.toString(), workspaceId.toString(), null, FILTER);
		verify(accounts, never()).findBuyerReference(tenantId.toString(), workspaceId.toString(), membershipId.toString());
	}

	@Test
	void salesOwnerReadsAreScopedToTheActiveAccountsAssignedToItsMembership() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		SalesOrderPersistencePort persistence = mock(SalesOrderPersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		UUID firstAccountId = UUID.randomUUID();
		UUID secondAccountId = UUID.randomUUID();
		SalesOrderSalesOwnerScope ownerScope = new SalesOrderSalesOwnerScope(Set.of(firstAccountId, secondAccountId));
		when(context.allows(PermissionKey.SALES_ORDER_READ)).thenReturn(true);
		when(context.hasRole(MembershipRole.BUYER)).thenReturn(false);
		when(context.hasRole(MembershipRole.COMPANY_OWNER)).thenReturn(false);
		when(context.tenantId()).thenReturn(new TenantId(tenantId));
		when(context.workspaceId()).thenReturn(new WorkspaceId(workspaceId));
		when(context.membershipId()).thenReturn(new MembershipId(membershipId));
		when(accounts.lockActiveSalesOwnerAccountIds(tenantId.toString(), workspaceId.toString(), membershipId.toString()))
				.thenReturn(List.of(firstAccountId.toString(), secondAccountId.toString()));
		SalesPage<com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView> expected =
				new SalesPage<>(List.of(), 0, 10, 0);
		when(persistence.listForSalesOwner(tenantId.toString(), workspaceId.toString(), ownerScope, FILTER))
				.thenReturn(expected);
		when(persistence.findForSalesOwner(tenantId.toString(), workspaceId.toString(), ownerScope, "order-id"))
				.thenReturn(Optional.empty());
		when(persistence.eventsForSalesOwner(tenantId.toString(), workspaceId.toString(), ownerScope, "order-id"))
				.thenReturn(List.of());
		SalesOrderService service = service(persistence, accounts);

		assertThat(service.list(context, FILTER)).isSameAs(expected);
		assertThatThrownBy(() -> service.detail(context, "order-id"))
				.isInstanceOf(com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException.class);
		assertThat(service.events(context, "order-id")).isEmpty();

		verify(context, org.mockito.Mockito.times(3)).requirePermission(PermissionKey.SALES_ORDER_READ);
		verify(accounts, org.mockito.Mockito.times(3)).lockActiveSalesOwnerAccountIds(
				tenantId.toString(), workspaceId.toString(), membershipId.toString());
		verify(persistence).listForSalesOwner(tenantId.toString(), workspaceId.toString(), ownerScope, FILTER);
		verify(persistence).findForSalesOwner(tenantId.toString(), workspaceId.toString(), ownerScope, "order-id");
		verify(persistence).eventsForSalesOwner(tenantId.toString(), workspaceId.toString(), ownerScope, "order-id");
		verify(persistence, never()).list(tenantId.toString(), workspaceId.toString(), null, FILTER);
		verify(persistence, never()).find(tenantId.toString(), workspaceId.toString(), null, "order-id");
		verify(persistence, never()).events(tenantId.toString(), workspaceId.toString(), null, "order-id");
	}

	@Test
	void salesOwnerWithoutActiveAssignedAccountsGetsEmptyListAndScopedNotFoundReads() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		SalesOrderPersistencePort persistence = mock(SalesOrderPersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		SalesOrderSalesOwnerScope emptyScope = new SalesOrderSalesOwnerScope(Set.of());
		when(context.allows(PermissionKey.SALES_ORDER_READ)).thenReturn(true);
		when(context.hasRole(MembershipRole.BUYER)).thenReturn(false);
		when(context.hasRole(MembershipRole.COMPANY_OWNER)).thenReturn(false);
		when(context.tenantId()).thenReturn(new TenantId(tenantId));
		when(context.workspaceId()).thenReturn(new WorkspaceId(workspaceId));
		when(context.membershipId()).thenReturn(new MembershipId(membershipId));
		when(accounts.lockActiveSalesOwnerAccountIds(tenantId.toString(), workspaceId.toString(), membershipId.toString()))
				.thenReturn(List.of());
		SalesPage<com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView> emptyPage =
				new SalesPage<>(List.of(), 0, 10, 0);
		when(persistence.listForSalesOwner(tenantId.toString(), workspaceId.toString(), emptyScope, FILTER))
				.thenReturn(emptyPage);
		when(persistence.findForSalesOwner(tenantId.toString(), workspaceId.toString(), emptyScope, "order-id"))
				.thenReturn(Optional.empty());
		when(persistence.eventsForSalesOwner(tenantId.toString(), workspaceId.toString(), emptyScope, "order-id"))
				.thenThrow(new com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException("sales-order"));
		SalesOrderService service = service(persistence, accounts);

		assertThat(service.list(context, FILTER)).isSameAs(emptyPage);
		assertThatThrownBy(() -> service.detail(context, "order-id"))
				.isInstanceOf(com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException.class);
		assertThatThrownBy(() -> service.events(context, "order-id"))
				.isInstanceOf(com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException.class);

		verify(persistence).listForSalesOwner(tenantId.toString(), workspaceId.toString(), emptyScope, FILTER);
		verify(persistence).findForSalesOwner(tenantId.toString(), workspaceId.toString(), emptyScope, "order-id");
		verify(persistence).eventsForSalesOwner(tenantId.toString(), workspaceId.toString(), emptyScope, "order-id");
		verify(persistence, never()).list(tenantId.toString(), workspaceId.toString(), null, FILTER);
	}

	private static SalesOrderService service(SalesOrderPersistencePort persistence, CustomerAccountQuery accounts) {
		return new SalesOrderService(persistence, accounts, mock(SalesOrderAggregatePersistencePort.class),
				mock(SalesOrderConversionPersistencePort.class));
	}
}
