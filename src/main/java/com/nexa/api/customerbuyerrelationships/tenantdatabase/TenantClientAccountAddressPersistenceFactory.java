package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-02 Customer Account address persistence to one router-owned Tenant JDBC session. */
@FunctionalInterface
public interface TenantClientAccountAddressPersistenceFactory {
    ClientAccountAddressPersistencePort bindTo(JdbcTemplate tenantJdbc);
}
