package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.LegacyCustomerCreditInitializationQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantLegacyCustomerCreditInitializationQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Binds the BC-02 compatibility query to the exact JDBC session supplied by the Tenant router. */
@Component
@Profile("!test")
public final class JdbcTenantLegacyCustomerCreditInitializationQueryFactory
        implements TenantLegacyCustomerCreditInitializationQueryFactory {
    @Override
    public LegacyCustomerCreditInitializationQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcLegacyCustomerCreditInitializationQuery(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
