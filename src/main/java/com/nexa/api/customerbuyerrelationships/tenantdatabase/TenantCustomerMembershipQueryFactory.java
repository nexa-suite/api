package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerMembershipQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds the BC-02 Buyer-to-account relationship read to one verified Tenant JDBC session. */
@FunctionalInterface
public interface TenantCustomerMembershipQueryFactory {
    CustomerMembershipQuery bindTo(JdbcTemplate tenantJdbc);
}
