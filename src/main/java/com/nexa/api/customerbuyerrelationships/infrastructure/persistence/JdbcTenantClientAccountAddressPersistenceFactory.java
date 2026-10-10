package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantClientAccountAddressPersistenceFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-02 address persistence on the caller's Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantClientAccountAddressPersistenceFactory
        implements TenantClientAccountAddressPersistenceFactory {
    @Override
    public ClientAccountAddressPersistencePort bindTo(JdbcTemplate tenantJdbc) {
        return new ClientAccountAddressPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
