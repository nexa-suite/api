package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.WarehouseEventContextQueryPort;
import com.nexa.api.inventoryavailability.infrastructure.events.JdbcWarehouseEventContextQueryAdapter;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseEventContextQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05's reservation event projection on the exact routed Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantWarehouseEventContextQueryFactory implements TenantWarehouseEventContextQueryFactory {
    @Override
    public WarehouseEventContextQueryPort bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcWarehouseEventContextQueryAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
