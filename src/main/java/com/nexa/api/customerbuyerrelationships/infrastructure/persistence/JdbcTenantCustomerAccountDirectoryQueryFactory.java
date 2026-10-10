package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDirectoryQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountDirectoryQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates a BC-02 directory query bound to the exact Tenant JDBC session supplied by runtime composition. */
@Component
@Profile("!test")
public final class JdbcTenantCustomerAccountDirectoryQueryFactory implements TenantCustomerAccountDirectoryQueryFactory {
    @Override
    public CustomerAccountDirectoryQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcCustomerAccountDirectoryQueryAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
