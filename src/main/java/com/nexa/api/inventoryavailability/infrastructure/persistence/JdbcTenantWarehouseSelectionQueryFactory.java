package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseSelectionQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05's existing query adapter on the caller's Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantWarehouseSelectionQueryFactory implements TenantWarehouseSelectionQueryFactory {
    @Override
    public WarehouseSelectionQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcWarehouseSelectionQuery(Objects.requireNonNull(tenantJdbc,
                "Tenant JDBC session is required"));
    }
}
