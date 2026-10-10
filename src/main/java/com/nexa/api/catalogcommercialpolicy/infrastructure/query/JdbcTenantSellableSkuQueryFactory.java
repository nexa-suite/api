package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-03 offer and SKU adapters on one verified Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantSellableSkuQueryFactory implements TenantSellableSkuQueryFactory {
    @Override
    public SellableSkuQuery bindTo(JdbcTemplate tenantJdbc, CatalogClientAccountPort tenantClientAccounts) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        CatalogClientAccountPort accounts = Objects.requireNonNull(tenantClientAccounts,
                "Tenant-bound Buyer account profiles are required");
        var offers = new JdbcAuthoritativeOfferQuery(jdbc, accounts);
        return new JdbcSellableSkuQuery(jdbc, offers);
    }
}
