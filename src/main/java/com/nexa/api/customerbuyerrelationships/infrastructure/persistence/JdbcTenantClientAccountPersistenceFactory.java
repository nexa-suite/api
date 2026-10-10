package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantClientAccountPersistenceFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-02 account persistence on the caller's Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantClientAccountPersistenceFactory implements TenantClientAccountPersistenceFactory {
    @Override
    public ClientAccountPersistencePort bindTo(JdbcTemplate tenantJdbc) {
        return new ClientAccountPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
