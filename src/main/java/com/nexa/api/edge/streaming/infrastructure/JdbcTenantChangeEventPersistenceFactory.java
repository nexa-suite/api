package com.nexa.api.edge.streaming.infrastructure;

import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates the edge-owned change-feed adapter on the exact callback session. */
@Component
@Profile("!test")
public final class JdbcTenantChangeEventPersistenceFactory implements TenantChangeEventPersistenceFactory {
    @Override
    public ChangeEventPersistencePort bindTo(JdbcTemplate tenantJdbc) {
        return new ChangeEventPersistenceAdapter(Objects.requireNonNull(tenantJdbc,
                "Tenant JDBC session is required"));
    }
}
