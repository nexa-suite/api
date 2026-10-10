package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-02 Customer Account persistence to one router-owned Tenant JDBC session. */
@FunctionalInterface
public interface TenantClientAccountPersistenceFactory {
    ClientAccountPersistencePort bindTo(JdbcTemplate tenantJdbc);
}
