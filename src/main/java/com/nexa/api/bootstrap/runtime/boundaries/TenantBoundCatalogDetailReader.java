package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.TenantCatalogHttpReadPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit Tenant-bound composition for catalog reads. It is deliberately not
 * used by the existing HTTP controller; callers must opt in to this use case.
 */
public final class TenantBoundCatalogDetailReader implements TenantCatalogHttpReadPort {
	private final TenantBusinessDatabaseRouter router;
	private final TenantCustomerAccountQueryFactory customerAccounts;
	private final TenantCatalogDetailQueryFactory catalogDetails;
	private final TenantCatalogAvailabilityAdapterFactory catalogAvailability;

	public TenantBoundCatalogDetailReader(TenantBusinessDatabaseRouter router,
			TenantCustomerAccountQueryFactory customerAccounts,
			TenantCatalogDetailQueryFactory catalogDetails,
			TenantCatalogAvailabilityAdapterFactory catalogAvailability) {
		this.router = Objects.requireNonNull(router, "Tenant business database router is required");
		this.customerAccounts = Objects.requireNonNull(customerAccounts, "Tenant Customer Account query factory is required");
		this.catalogDetails = Objects.requireNonNull(catalogDetails, "Tenant Catalog query factory is required");
		this.catalogAvailability = Objects.requireNonNull(catalogAvailability,
				"Tenant Catalog Availability adapter factory is required");
	}

	@Override
	public CatalogItemDetail getByCatalogItemId(CurrentAccessContext accessContext, String catalogItemId) {
		Objects.requireNonNull(accessContext, "Verified access context is required");
		catalogDetails.requireCatalogRead();
		return router.inTransaction(accessContext, jdbc -> readWithinTenant(accessContext, catalogItemId, jdbc));
	}

	@Override
	public CatalogPage<CatalogItemSummary> list(CurrentAccessContext accessContext,
			CatalogSearchCriteria criteria) {
		Objects.requireNonNull(accessContext, "Verified access context is required");
		Objects.requireNonNull(criteria, "Catalog search criteria is required");
		catalogDetails.requireCatalogRead();
		return router.inTransaction(accessContext, tenantJdbc -> catalogReadQuery(accessContext, tenantJdbc)
				.list(accessContext.tenantId().value(), accessContext.workspaceId().value(), criteria));
	}

	public CatalogPage<CatalogItemSummary> listCatalogItems(CurrentAccessContext accessContext,
			CatalogSearchCriteria criteria) {
		return list(accessContext, criteria);
	}

	private CatalogItemDetail readWithinTenant(CurrentAccessContext accessContext, String catalogItemId,
			JdbcTemplate tenantJdbc) {
		UUID tenantId = accessContext.tenantId().value();
		UUID workspaceId = accessContext.workspaceId().value();
		return catalogReadQuery(accessContext, tenantJdbc).getByCatalogItemId(tenantId, workspaceId, catalogItemId);
	}

	private TenantCatalogDetailQueryFactory.TenantCatalogReadQuery catalogReadQuery(
			CurrentAccessContext accessContext, JdbcTemplate tenantJdbc) {
		CatalogClientAccountPort.ClientAccountProfile buyerProfile = accessContext.hasRole(MembershipRole.BUYER)
				? resolveBuyer(accessContext, tenantJdbc)
				: null;
		// Each owner factory binds its adapter to this one routed JDBC session.
		return catalogDetails.bindTo(tenantJdbc, buyerProfile, catalogAvailability::bindTo);
	}

	private CatalogClientAccountPort.ClientAccountProfile resolveBuyer(CurrentAccessContext accessContext,
			JdbcTemplate tenantJdbc) {
		CustomerAccountQuery tenantAccountQuery = Objects.requireNonNull(customerAccounts.bindTo(tenantJdbc),
				"Tenant Customer Account query factory returned no query");
		CustomerAccountDetails account = tenantAccountQuery.findActiveBuyerDetails(
				accessContext.tenantId().toString(), accessContext.workspaceId().toString(),
				accessContext.membershipId().toString())
			.orElseThrow(() -> new AccessDeniedException("Active Buyer relationship is required"));
		return new CatalogClientAccountPort.ClientAccountProfile(
				UUID.fromString(account.id()), account.segment(), null);
	}
}
