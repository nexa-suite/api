package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-06 inventory projections on the exact routed Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantFulfillmentInventoryQueryFactory implements TenantFulfillmentInventoryQueryFactory {
    @Override
    public FulfillmentInventoryQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcFulfillmentInventoryQuery(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
