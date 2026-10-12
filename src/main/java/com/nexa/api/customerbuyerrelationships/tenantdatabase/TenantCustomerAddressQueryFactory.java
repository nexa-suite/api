package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAddressQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-02 address reads to the JDBC session of one routed Tenant transaction. */
@FunctionalInterface
public interface TenantCustomerAddressQueryFactory {
    CustomerAddressQuery bindTo(JdbcTemplate tenantJdbc);
}
