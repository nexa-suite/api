package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.publicapi.LegacyCustomerCreditInitializationQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds legacy credit initialization facts to the caller's Tenant transaction. */
@FunctionalInterface
public interface TenantLegacyCustomerCreditInitializationQueryFactory {
    LegacyCustomerCreditInitializationQuery bindTo(JdbcTemplate tenantJdbc);
}
