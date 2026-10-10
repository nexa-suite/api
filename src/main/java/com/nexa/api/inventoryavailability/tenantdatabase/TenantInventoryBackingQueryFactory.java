package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 backing reads to one routed Tenant session. */
@FunctionalInterface
public interface TenantInventoryBackingQueryFactory {
    InventoryBackingQuery bindTo(JdbcTemplate tenantJdbc);
}
