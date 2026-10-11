package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogAvailabilityQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestCompositionFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDetailQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.RegionalCurrencyQuery;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Routes Catalog requests and delegates request-local composition to the owning BC-03 module. */
public final class TenantBoundCatalogCommercialPolicyRequestAdapter
        implements TenantCatalogCommercialPolicyRequestPort {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantSellableSkuQueryFactory sellableSkus;
    private final TenantCatalogAvailabilityAdapterFactory availabilityAdapters;
    private final TenantCatalogCommercialPolicyRequestCompositionFactory compositions;
    private final RegionalCurrencyQuery regionalCurrency;

    public TenantBoundCatalogCommercialPolicyRequestAdapter(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts, TenantSellableSkuQueryFactory sellableSkus,
            TenantCatalogAvailabilityAdapterFactory availabilityAdapters,
            TenantCatalogCommercialPolicyRequestCompositionFactory compositions,
            RegionalCurrencyQuery regionalCurrency) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts,
                "Tenant Customer Account factory is required");
        this.sellableSkus = Objects.requireNonNull(sellableSkus, "Tenant Sellable SKU factory is required");
        this.availabilityAdapters = Objects.requireNonNull(availabilityAdapters,
                "Tenant Catalog Availability factory is required");
        this.compositions = Objects.requireNonNull(compositions,
                "Tenant Catalog composition factory is required");
        this.regionalCurrency = Objects.requireNonNull(regionalCurrency, "Regional currency query is required");
    }

    @Override
    public <T> T inRequest(CurrentAccessContext accessContext, Work<T> work) {
        CurrentAccessContext context = Objects.requireNonNull(accessContext,
                "Verified Tenant access context is required");
        Work<T> operation = Objects.requireNonNull(work, "Tenant Catalog work is required");
        UUID tenantId = context.tenantId().value();
        Optional<String> regionalCurrencySnapshot = Objects.requireNonNull(
                regionalCurrency.findCurrency(tenantId), "Regional currency query returned no result");
        return router.inTransaction(context, tenantJdbc -> operation.execute(bind(
                context, tenantJdbc, regionalCurrencySnapshot)));
    }

    private Request bind(CurrentAccessContext accessContext, JdbcTemplate tenantJdbc,
            Optional<String> regionalCurrencySnapshot) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Routed Tenant JDBC session is required");
        CustomerAccountQuery accounts = Objects.requireNonNull(customerAccounts.bindTo(jdbc),
                "Tenant Customer Account factory returned no query");
        CatalogClientAccountPort tenantClientAccounts = new CatalogClientAccountCompositionAdapter(accounts);
        SellableSkuQuery tenantSellableSkus = Objects.requireNonNull(
                sellableSkus.bindTo(jdbc, tenantClientAccounts),
                "Tenant Sellable SKU factory returned no query");
        CatalogAvailabilityQuery tenantAvailability = Objects.requireNonNull(
                availabilityAdapters.bindTo(jdbc, tenantSellableSkus),
                "Tenant Catalog Availability factory returned no adapter");
        TenantCatalogDetailQueryFactory.TenantAvailabilityAdapterFactory detailAvailability =
                availabilityAdapters::bindTo;
        return Objects.requireNonNull(compositions.bindTo(accessContext, jdbc, tenantClientAccounts,
                        tenantAvailability, detailAvailability, regionalCurrencySnapshot),
                "Tenant Catalog composition factory returned no request");
    }
}
