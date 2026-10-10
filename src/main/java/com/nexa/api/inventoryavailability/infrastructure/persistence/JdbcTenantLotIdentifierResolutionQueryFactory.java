package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.LotIdentifierResolutionQuery;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantLotIdentifierResolutionQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05's read adapter on the exact routed Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantLotIdentifierResolutionQueryFactory
        implements TenantLotIdentifierResolutionQueryFactory {
    @Override
    public LotIdentifierResolutionQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcLotIdentifierResolutionQuery(Objects.requireNonNull(tenantJdbc,
                "Tenant JDBC session is required"));
    }
}
