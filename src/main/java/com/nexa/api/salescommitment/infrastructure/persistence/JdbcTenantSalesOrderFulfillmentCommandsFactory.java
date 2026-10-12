package com.nexa.api.salescommitment.infrastructure.persistence;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentCommands;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentCommandsFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-04's existing fulfillment lifecycle command adapter on the routed Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantSalesOrderFulfillmentCommandsFactory
        implements TenantSalesOrderFulfillmentCommandsFactory {
    @Override
    public SalesOrderFulfillmentCommands bindTo(JdbcTemplate tenantJdbc) {
        return new SalesOrderFulfillmentPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
