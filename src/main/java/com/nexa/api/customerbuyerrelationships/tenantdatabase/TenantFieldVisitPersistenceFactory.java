package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-02 field-visit evidence persistence to one router-owned Tenant JDBC session. */
@FunctionalInterface
public interface TenantFieldVisitPersistenceFactory {
    FieldVisitPersistencePort bindTo(JdbcTemplate tenantJdbc);
}
