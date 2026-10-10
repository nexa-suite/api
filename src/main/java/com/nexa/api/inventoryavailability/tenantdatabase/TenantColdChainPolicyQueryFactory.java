package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 delivery and lot temperature policy reads to one routed Tenant session. */
@FunctionalInterface
public interface TenantColdChainPolicyQueryFactory {
    ColdChainPolicyQuery bindTo(JdbcTemplate tenantJdbc, InventoryFulfillmentSource tenantFulfillmentSource,
                                SellableSkuQuery tenantSellableSkus);
}
