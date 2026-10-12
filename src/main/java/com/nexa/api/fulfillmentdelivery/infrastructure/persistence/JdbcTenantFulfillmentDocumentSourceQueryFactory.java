package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDocumentSourceQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-06's document projections on the exact routed Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantFulfillmentDocumentSourceQueryFactory
        implements TenantFulfillmentDocumentSourceQueryFactory {
    @Override
    public FulfillmentDocumentSourceQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcFulfillmentDocumentSourceQuery(Objects.requireNonNull(
                tenantJdbc, "Tenant JDBC session is required"));
    }
}
