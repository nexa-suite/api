package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingQuery;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryBackingQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05's backing projection on the exact routed Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantInventoryBackingQueryFactory implements TenantInventoryBackingQueryFactory {
    @Override
    public InventoryBackingQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcInventoryBackingQuery(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
