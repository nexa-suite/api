package com.nexa.api.fulfillmentdelivery.tenantdatabase;

import com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsEventContextQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-06's event context reads to one routed Tenant JDBC session. */
@FunctionalInterface
public interface TenantLogisticsEventContextQueryFactory {
    LogisticsEventContextQueryPort bindTo(JdbcTemplate tenantJdbc);
}
