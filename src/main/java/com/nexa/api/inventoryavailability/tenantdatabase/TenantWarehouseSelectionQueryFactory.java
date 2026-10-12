package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 warehouse selection to one routed Tenant database session. */
@FunctionalInterface
public interface TenantWarehouseSelectionQueryFactory {
    WarehouseSelectionQuery bindTo(JdbcTemplate tenantJdbc);
}
