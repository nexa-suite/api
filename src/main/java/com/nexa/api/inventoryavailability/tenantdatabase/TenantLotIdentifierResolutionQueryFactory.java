package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.inventoryavailability.application.publicapi.LotIdentifierResolutionQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 lot identifier lookup to a routed Tenant session. */
@FunctionalInterface
public interface TenantLotIdentifierResolutionQueryFactory {
    LotIdentifierResolutionQuery bindTo(JdbcTemplate tenantJdbc);
}
