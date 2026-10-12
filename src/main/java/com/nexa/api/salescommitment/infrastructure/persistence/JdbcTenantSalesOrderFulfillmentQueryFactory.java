package com.nexa.api.salescommitment.infrastructure.persistence;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses the Sales-owned fulfillment query adapter on the caller's Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantSalesOrderFulfillmentQueryFactory implements TenantSalesOrderFulfillmentQueryFactory {
    @Override
    public SalesOrderFulfillmentQuery bindTo(JdbcTemplate tenantJdbc) {
        return new SalesOrderFulfillmentPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
