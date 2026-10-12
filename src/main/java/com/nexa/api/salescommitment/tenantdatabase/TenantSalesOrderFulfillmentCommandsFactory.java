package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentCommands;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-04 fulfillment lifecycle commands to one routed Tenant session. */
@FunctionalInterface
public interface TenantSalesOrderFulfillmentCommandsFactory {
    SalesOrderFulfillmentCommands bindTo(JdbcTemplate tenantJdbc);
}
