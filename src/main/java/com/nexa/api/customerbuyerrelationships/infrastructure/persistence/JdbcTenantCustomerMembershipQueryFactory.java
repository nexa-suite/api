package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerMembershipQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerMembershipQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Binds existing BC-02 membership-link query to caller's exact Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantCustomerMembershipQueryFactory implements TenantCustomerMembershipQueryFactory {
    @Override
    public CustomerMembershipQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcCustomerMembershipQuery(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
