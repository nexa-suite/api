package com.nexa.api.tenantaccessgovernance.tenantmanagement.application;

import com.nexa.api.bootstrap.runtime.boundaries.TenantBoundCustomerRelationshipsUseCase;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSortField;
import com.nexa.api.catalogcommercialpolicy.application.model.SortDirection;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogQueryService;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.CatalogAuthorizationAdapter;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.service.ClientAccountService;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.service.ClientAccountAddressService;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchCommandPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchQueryPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.service.StartDispatchRouteService;
import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestFilter;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestEventPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.service.PurchaseRequestService;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.service.ManualSalesOrderDraftService;
import com.nexa.api.salescommitment.application.salesorder.service.SalesOrderService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
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
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

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

class RoleFocusedReadAuthorizationTests {
	private static final long AUTHORIZATION_VERSION = 7;
	private static final SalesOrderFilter SALES_ORDER_FILTER = new SalesOrderFilter(null, 0, 10, "createdAt,desc");
	private static final PurchaseRequestFilter PURCHASE_REQUEST_FILTER =
			new PurchaseRequestFilter(null, null, null, null, null, 0, 10, "createdAt,desc");
	private final UUID tenantId = UUID.randomUUID();
	private final UUID workspaceId = UUID.randomUUID();
	private final String accountId = UUID.randomUUID().toString();
	private final SecurityContext originalSecurityContext = SecurityContextHolder.getContext();

	@AfterEach
	void restoreSecurityContext() {
		SecurityContextHolder.setContext(originalSecurityContext);
	}

	@Test
	void tenantAdministratorReadsClientAndCatalogButCannotReadSalesOrLogistics() {
		CurrentAccessContext admin = contextFor(MembershipRole.TENANT_ADMIN);
		assertThat(admin.allows(PermissionKey.CLIENT_READ)).isTrue();
		assertThat(admin.allows(PermissionKey.CATALOG_READ)).isTrue();
		/* client.read's legacy alias must not act as authority for Sales capability. */
		assertThat(admin.allows(Permission.SALES_READ)).isTrue();

		ClientAccountPersistencePort clientPersistence = mock(ClientAccountPersistencePort.class);
		CustomerAccountPage<ClientAccountView> clientPage = new CustomerAccountPage<>(List.of(), 0, 10, 0);
		when(clientPersistence.list(tenantId.toString(), workspaceId.toString(), null, null, 0, 10))
				.thenReturn(clientPage);
		ClientAccountService clients = new ClientAccountService(clientPersistence, mock(BuyerMembershipDirectory.class));
		assertThat(clients.list(admin, null, null, 0, 10)).isSameAs(clientPage);
		verify(clientPersistence).list(tenantId.toString(), workspaceId.toString(), null, null, 0, 10);

		CatalogItemQueryPort catalogQuery = mock(CatalogItemQueryPort.class);
		CatalogSearchCriteria criteria = new CatalogSearchCriteria("", null, null, null, 0, 10,
				CatalogSortField.ITEM_NAME, SortDirection.ASC);
		CatalogPage<com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary> catalogPage =
				new CatalogPage<>(List.of(), 0, 10, 0, CatalogSortField.ITEM_NAME, SortDirection.ASC);
		when(catalogQuery.search(criteria)).thenReturn(catalogPage);
		SecurityContext catalogSecurity = SecurityContextHolder.createEmptyContext();
		catalogSecurity.setAuthentication(new TestingAuthenticationToken("tenant-admin", "unused",
				admin.permissionCodes().stream().map(SimpleGrantedAuthority::new).toList()));
		SecurityContextHolder.setContext(catalogSecurity);
		assertThat(new CatalogQueryService(catalogQuery, new CatalogAuthorizationAdapter()).list(criteria))
				.isSameAs(catalogPage);
		verify(catalogQuery).search(criteria);

		SalesOrderPersistencePort salesOrders = mock(SalesOrderPersistencePort.class);
		SalesOrderService salesOrderService = new SalesOrderService(salesOrders, mock(CustomerAccountQuery.class),
				mock(SalesOrderAggregatePersistencePort.class), mock(SalesOrderConversionPersistencePort.class));
		PurchaseRequestPersistencePort purchaseRequests = mock(PurchaseRequestPersistencePort.class);
		PurchaseRequestService purchaseRequestService = new PurchaseRequestService(purchaseRequests,
				mock(PurchaseRequestEventPersistencePort.class), mock(IdempotencyPersistencePort.class),
				mock(CatalogItemSnapshotLookupPort.class), mock(CustomerAccountQuery.class));
		ManualSalesOrderDraftPersistencePort drafts = mock(ManualSalesOrderDraftPersistencePort.class);
		ManualSalesOrderDraftService draftService = new ManualSalesOrderDraftService(drafts,
				mock(ManualSalesOrderUseCase.class), mock(ManualSalesOrderPersistencePort.class));
		DispatchQueryPersistencePort dispatches = mock(DispatchQueryPersistencePort.class);
		LogisticsOperationsService logistics = new LogisticsOperationsService(dispatches,
				mock(DispatchCommandPersistencePort.class), mock(CustomerAccountQuery.class),
				mock(StartDispatchRouteService.class));

		assertThatThrownBy(() -> salesOrderService.list(admin, SALES_ORDER_FILTER))
				.isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> purchaseRequestService.list(admin, PURCHASE_REQUEST_FILTER))
				.isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> draftService.get(admin, UUID.randomUUID()))
				.isInstanceOf(AccessPolicyViolation.class);
		assertThatThrownBy(() -> logistics.list(admin, null, 0, 10, "createdAt,desc"))
				.isInstanceOf(AccessPolicyViolation.class);
		verifyNoInteractions(salesOrders, purchaseRequests, drafts, dispatches);
	}

	@Test
	void salesPresetPassesEachReadFilterAndKeepsSalesSafeLogisticsProjection() {
		CurrentAccessContext sales = contextFor(MembershipRole.SALES);
		SalesOrderPersistencePort orders = mock(SalesOrderPersistencePort.class);
		SalesPage<com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView> ordersPage =
				new SalesPage<>(List.of(), 0, 10, 0);
		when(orders.list(tenantId.toString(), workspaceId.toString(), null, SALES_ORDER_FILTER)).thenReturn(ordersPage);
		SalesOrderService salesOrderService = new SalesOrderService(orders, mock(CustomerAccountQuery.class),
				mock(SalesOrderAggregatePersistencePort.class), mock(SalesOrderConversionPersistencePort.class));
		assertThat(salesOrderService.list(sales, SALES_ORDER_FILTER)).isSameAs(ordersPage);
		verify(orders).list(tenantId.toString(), workspaceId.toString(), null, SALES_ORDER_FILTER);

		PurchaseRequestPersistencePort requests = mock(PurchaseRequestPersistencePort.class);
		SalesPage<com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView> requestsPage =
				new SalesPage<>(List.of(), 0, 10, 0);
		when(requests.list(tenantId.toString(), workspaceId.toString(), null, PURCHASE_REQUEST_FILTER))
				.thenReturn(requestsPage);
		PurchaseRequestService requestService = new PurchaseRequestService(requests,
				mock(PurchaseRequestEventPersistencePort.class), mock(IdempotencyPersistencePort.class),
				mock(CatalogItemSnapshotLookupPort.class), mock(CustomerAccountQuery.class));
		assertThat(requestService.list(sales, PURCHASE_REQUEST_FILTER)).isSameAs(requestsPage);
		verify(requests).list(tenantId.toString(), workspaceId.toString(), null, PURCHASE_REQUEST_FILTER);

		UUID draftId = UUID.randomUUID();
		ManualSalesOrderDraftPersistencePort drafts = mock(ManualSalesOrderDraftPersistencePort.class);
		ManualSalesOrderDraftService draftService = new ManualSalesOrderDraftService(drafts,
				mock(ManualSalesOrderUseCase.class), mock(ManualSalesOrderPersistencePort.class));
		assertThat(draftService.get(sales, draftId)).isNull();
		verify(drafts).get(sales, draftId);

		DispatchQueryPersistencePort dispatchQueries = mock(DispatchQueryPersistencePort.class);
		LogisticsOperationsModels.DispatchView dispatch = new LogisticsOperationsModels.DispatchView(
				"dispatch-id", "D-1", "reservation-private", "order-private", "SO-1", "account-private",
				"C-1", "Client", "IN_ROUTE", "destination", "area", "NORMAL", null, null, null,
				new LogisticsOperationsModels.AssignmentView("membership", "driver", "vehicle", "route"),
				java.math.BigDecimal.ZERO, java.math.BigDecimal.TEN, "C", "OK", "pod-private", "COMPLETED",
				1, Instant.EPOCH, List.of("INTERNAL_ALERT"), null, "continuation-private", "IN_ROUTE", List.of());
		LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> dispatchPage =
				new LogisticsOperationsModels.Page<>(List.of(dispatch), 0, 10, 1);
		when(dispatchQueries.list(tenantId.toString(), workspaceId.toString(), null, "IN_ROUTE", 0, 10,
				"createdAt,desc")).thenReturn(dispatchPage);
		LogisticsOperationsService logistics = new LogisticsOperationsService(dispatchQueries,
				mock(DispatchCommandPersistencePort.class), mock(CustomerAccountQuery.class),
				mock(StartDispatchRouteService.class));
		LogisticsOperationsModels.DispatchView salesSafe = logistics.list(sales, "IN_ROUTE", 0, 10,
				"createdAt,desc").items().getFirst();
		assertThat(salesSafe.reservationId()).isNull();
		assertThat(salesSafe.salesOrderId()).isNull();
		assertThat(salesSafe.clientAccountId()).isNull();
		assertThat(salesSafe.assignment()).isNull();
		assertThat(salesSafe.temperatureStatus()).isNull();
		verify(dispatchQueries).list(tenantId.toString(), workspaceId.toString(), null, "IN_ROUTE", 0, 10,
				"createdAt,desc");
	}

	@Test
	void tenantAdministratorCanReadClientAddressesButCannotManageThem() {
		CurrentAccessContext admin = contextFor(MembershipRole.TENANT_ADMIN);
		ClientAccountAddressPersistencePort persistence = mock(ClientAccountAddressPersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		when(accounts.findReference(tenantId.toString(), workspaceId.toString(), accountId))
				.thenReturn(Optional.of(new CustomerAccountReference(accountId, "ACTIVE")));
		when(persistence.list(tenantId.toString(), workspaceId.toString(), accountId)).thenReturn(List.of());
		ClientAccountAddressService addresses = new ClientAccountAddressService(persistence, accounts);

		assertThat(addresses.list(admin, accountId)).isEmpty();
		verify(accounts).findReference(tenantId.toString(), workspaceId.toString(), accountId);
		verify(persistence).list(tenantId.toString(), workspaceId.toString(), accountId);
		assertThat(admin.allows(PermissionKey.CLIENT_ADDRESS_MANAGE)).isFalse();
		assertThatThrownBy(() -> addresses.create(admin, accountId, null))
				.isInstanceOf(AccessPolicyViolation.class);
		verify(persistence, never()).insert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	void clientMutationsRejectSalesOrderAndAddressManageAliases() {
		ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
		BuyerMembershipDirectory buyers = mock(BuyerMembershipDirectory.class);
		ClientAccountService clients = new ClientAccountService(persistence, buyers);
		List<CurrentAccessContext> aliasOnlyContexts = List.of(
				contextWithPermissions("order.manage.only", Set.of(PermissionKey.SALES_ORDER_MANAGE)),
				contextWithPermissions("address.manage.only", Set.of(PermissionKey.CLIENT_ADDRESS_MANAGE)));
		assertThat(aliasOnlyContexts.getFirst().allows(Permission.SALES_WRITE)).isTrue();

		for (CurrentAccessContext context : aliasOnlyContexts) {
			assertThat(context.allows(PermissionKey.CLIENT_MANAGE)).isFalse();
			assertThatThrownBy(() -> clients.create(context, null)).isInstanceOf(AccessPolicyViolation.class);
			assertThatThrownBy(() -> clients.update(context, accountId, null, 0))
					.isInstanceOf(AccessPolicyViolation.class);
			assertThatThrownBy(() -> clients.changeStatus(context, accountId, "ACTIVE", 0))
					.isInstanceOf(AccessPolicyViolation.class);
			assertThatThrownBy(() -> clients.associateBuyer(context, accountId, UUID.randomUUID().toString(), 0))
					.isInstanceOf(AccessPolicyViolation.class);
		}

		verifyNoInteractions(persistence, buyers);
	}

	@Test
	void clientReadAndManageGrantAllowsClientAccountMutation() {
		CurrentAccessContext context = contextWithPermissions("client.manager",
				Set.of(PermissionKey.CLIENT_READ, PermissionKey.CLIENT_MANAGE));
		ClientAccountPersistencePort persistence = mock(ClientAccountPersistencePort.class);
		ClientAccountView command = new ClientAccountView(null, null, "Business", "Commercial", null,
				null, null, null, "Contact", "contact@example.test", "555-0100", "STANDARD", "NET30",
				null, null, 0);
		ClientAccountView persisted = new ClientAccountView(accountId, "C-1", "Business", "Commercial", "PE",
				"RUC", "20123456789", "GENERAL", "Contact", "contact@example.test", "555-0100",
				"STANDARD", "NET30", "ACTIVE", null, 1);
		when(persistence.update(tenantId.toString(), workspaceId.toString(), accountId, "Business", "Commercial",
				"Contact", "contact@example.test", "555-0100", "STANDARD", "NET30", 0)).thenReturn(1);
		when(persistence.find(tenantId.toString(), workspaceId.toString(), accountId)).thenReturn(Optional.of(persisted));
		ClientAccountService clients = new ClientAccountService(persistence, mock(BuyerMembershipDirectory.class));

		assertThat(context.allows(PermissionKey.CLIENT_READ)).isTrue();
		assertThat(context.allows(PermissionKey.CLIENT_MANAGE)).isTrue();
		assertThat(clients.update(context, accountId, command, 0)).isSameAs(persisted);
		verify(persistence).update(tenantId.toString(), workspaceId.toString(), accountId, "Business", "Commercial",
				"Contact", "contact@example.test", "555-0100", "STANDARD", "NET30", 0);
	}

	private CurrentAccessContext contextFor(MembershipRole role) {
		RoleDefinition definition = role == MembershipRole.TENANT_ADMIN
				? RoleDefinition.systemReserved(role, Instant.EPOCH)
				: RoleDefinition.systemTemplate(role, Instant.EPOCH);
		return contextFor(definition, Set.of(role));
	}

	private CurrentAccessContext contextWithPermissions(String roleCode, Set<PermissionKey> permissions) {
		RoleDefinition definition = RoleDefinition.custom(new TenantId(tenantId), new WorkspaceId(workspaceId),
				roleCode, roleCode, "authorization test", permissions, UserId.random(), Instant.EPOCH);
		return contextFor(definition, Set.of());
	}

	private CurrentAccessContext contextFor(RoleDefinition definition, Set<MembershipRole> roles) {
		EffectiveAuthorization authorization = EffectiveAuthorization.of(List.of(definition), Set.of(),
				AUTHORIZATION_VERSION);
		Membership membership = new Membership(MembershipId.random(), UserId.random(), new TenantId(tenantId),
				new WorkspaceId(workspaceId), roles, authorization.roleCodes(), authorization.roleDefinitionIds(),
				MembershipStatus.ACTIVE, AUTHORIZATION_VERSION);
		return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
				WorkspaceStatus.ACTIVE, authorization), Surface.PLATFORM);
	}
}
