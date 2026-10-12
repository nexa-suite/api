package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.inventoryavailability.application.publicapi.WarehouseEventContextQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 reservation event context reads to one routed Tenant session. */
@FunctionalInterface
public interface TenantWarehouseEventContextQueryFactory {
    WarehouseEventContextQueryPort bindTo(JdbcTemplate tenantJdbc);
}
