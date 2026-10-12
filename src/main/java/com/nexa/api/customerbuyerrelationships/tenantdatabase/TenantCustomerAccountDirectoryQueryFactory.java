package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDirectoryQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds the minimal BC-02 account directory query to the caller's verified Tenant transaction. */
@FunctionalInterface
public interface TenantCustomerAccountDirectoryQueryFactory {
    CustomerAccountDirectoryQuery bindTo(JdbcTemplate tenantJdbc);
}
