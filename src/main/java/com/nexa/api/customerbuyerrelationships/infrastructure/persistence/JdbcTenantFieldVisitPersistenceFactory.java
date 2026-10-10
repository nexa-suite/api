package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitPersistencePort;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantFieldVisitPersistenceFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-02 field-visit persistence on the caller's Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantFieldVisitPersistenceFactory implements TenantFieldVisitPersistenceFactory {
    @Override
    public FieldVisitPersistencePort bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcFieldVisitPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
