package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-04's read contract for fulfillment and finance evidence to one Tenant session. */
@FunctionalInterface
public interface TenantSalesOrderFulfillmentQueryFactory {
    SalesOrderFulfillmentQuery bindTo(JdbcTemplate tenantJdbc);
}
