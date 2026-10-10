package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds authoritative BC-03 sellable SKU and offer queries to one Tenant session. */
@FunctionalInterface
public interface TenantSellableSkuQueryFactory {
    SellableSkuQuery bindTo(JdbcTemplate tenantJdbc, CatalogClientAccountPort tenantClientAccounts);
}
