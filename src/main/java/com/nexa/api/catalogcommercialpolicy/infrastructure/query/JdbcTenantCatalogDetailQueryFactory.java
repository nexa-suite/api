package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.AuthoritativeOfferQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogQueryService;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates BC-03-owned catalog and offer adapters on the exact Tenant JDBC session supplied by composition. */
@Component
@Profile("!test")
public final class JdbcTenantCatalogDetailQueryFactory implements TenantCatalogDetailQueryFactory {
	private final Clock clock;
	private final CatalogAuthorizationAdapter authorization = new CatalogAuthorizationAdapter();

	public JdbcTenantCatalogDetailQueryFactory(Clock clock) {
		this.clock = Objects.requireNonNull(clock, "Catalog clock is required");
	}

	@Override
	public void requireCatalogRead() {
		authorization.requireCatalogRead();
	}

	@Override
	public TenantCatalogReadQuery bindTo(JdbcTemplate tenantJdbc,
			CatalogClientAccountPort.ClientAccountProfile buyerProfile,
			TenantAvailabilityAdapterFactory availabilityFactory) {
		JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
		TenantAvailabilityAdapterFactory availabilityBinding = Objects.requireNonNull(availabilityFactory,
				"Tenant inventory adapter factory is required");
		CatalogClientAccountPort resolvedBuyer = resolvedBuyerProfile(buyerProfile);
		AuthoritativeOfferQuery offers = new JdbcAuthoritativeOfferQuery(jdbc, resolvedBuyer);
		var sellableSkus = new JdbcSellableSkuQuery(jdbc, offers);
		var availability = Objects.requireNonNull(availabilityBinding.bindTo(jdbc, sellableSkus),
				"Tenant inventory adapter factory returned no availability query");
		var itemQuery = new JdbcCatalogItemQueryAdapter(jdbc, availability, offers, clock);
		CatalogQueryService catalog = new CatalogQueryService(itemQuery, authorization);
		return new TenantCatalogReadQuery() {
			@Override
			public CatalogItemDetail getByCatalogItemId(UUID tenantId, UUID workspaceId, String catalogItemId) {
				return catalog.getByCatalogItemId(scope(tenantId, workspaceId), catalogItemId);
			}

			@Override
			public CatalogPage<CatalogItemSummary> list(UUID tenantId, UUID workspaceId,
					CatalogSearchCriteria criteria) {
				return catalog.list(scope(tenantId, workspaceId), criteria);
			}

			private CatalogScope scope(UUID tenantId, UUID workspaceId) {
				return buyerProfile == null
						? new CatalogScope(tenantId, workspaceId)
						: new CatalogScope(tenantId, workspaceId, true, buyerProfile.id(),
								buyerProfile.segment(), buyerProfile.buyerTier());
			}
		};
	}

	private static CatalogClientAccountPort resolvedBuyerProfile(
			CatalogClientAccountPort.ClientAccountProfile buyerProfile) {
		return new CatalogClientAccountPort() {
			@Override
			public Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
				return Optional.empty();
			}

			@Override
			public Optional<ClientAccountProfile> findProfileForMembership(UUID tenantId, UUID workspaceId,
					UUID membershipId) {
				return Optional.empty();
			}

			@Override
			public Optional<ClientAccountProfile> findActiveProfile(UUID tenantId, UUID workspaceId,
					UUID customerAccountId) {
				if (buyerProfile == null || !buyerProfile.id().equals(customerAccountId)) return Optional.empty();
				return Optional.of(buyerProfile);
			}
		};
	}
}
