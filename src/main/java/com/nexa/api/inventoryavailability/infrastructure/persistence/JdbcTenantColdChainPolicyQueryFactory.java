package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantColdChainPolicyQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05's existing temperature policy query on the exact routed Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantColdChainPolicyQueryFactory implements TenantColdChainPolicyQueryFactory {
    @Override
    public ColdChainPolicyQuery bindTo(JdbcTemplate tenantJdbc, InventoryFulfillmentSource tenantFulfillmentSource,
                                       SellableSkuQuery tenantSellableSkus) {
        return new JdbcColdChainPolicyQuery(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(tenantFulfillmentSource, "Tenant fulfillment source is required"),
                Objects.requireNonNull(tenantSellableSkus, "Tenant Sellable SKU query is required"));
    }
}
