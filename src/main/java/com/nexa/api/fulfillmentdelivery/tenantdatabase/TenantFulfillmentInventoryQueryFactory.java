package com.nexa.api.fulfillmentdelivery.tenantdatabase;

import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-06 fulfillment inventory reads to the routed Tenant JDBC session. */
@FunctionalInterface
public interface TenantFulfillmentInventoryQueryFactory {
    FulfillmentInventoryQuery bindTo(JdbcTemplate tenantJdbc);
}
