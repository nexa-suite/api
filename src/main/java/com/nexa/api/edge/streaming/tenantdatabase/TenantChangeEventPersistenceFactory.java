package com.nexa.api.edge.streaming.tenantdatabase;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds the integration change feed to one caller-selected Tenant JDBC session. */
@FunctionalInterface
public interface TenantChangeEventPersistenceFactory {
    ChangeEventPersistencePort bindTo(JdbcTemplate tenantJdbc);
}
