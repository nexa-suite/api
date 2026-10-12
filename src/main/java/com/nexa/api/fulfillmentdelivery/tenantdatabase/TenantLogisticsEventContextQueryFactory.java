package com.nexa.api.fulfillmentdelivery.tenantdatabase;

import com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsEventContextQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-06's event context reads to one routed Tenant JDBC session. */
@FunctionalInterface
@org.springframework.modulith.NamedInterface(value = "fulfillment-tenant-composition", propagate = false)
public interface TenantLogisticsEventContextQueryFactory {
    LogisticsEventContextQueryPort bindTo(JdbcTemplate tenantJdbc);
}
