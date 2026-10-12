package com.nexa.api.businesstraceability.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityCommandsFactory;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Creates a BC-11 adapter only from the explicit Tenant callback's JDBC session and outbox port. */
@Component
public final class JdbcTenantBusinessTraceabilityCommandsFactory implements TenantBusinessTraceabilityCommandsFactory {
    private final ObjectMapper mapper;

    public JdbcTenantBusinessTraceabilityCommandsFactory(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "Object mapper is required");
    }

    @Override
    public BusinessTraceabilityCommands bindTo(JdbcTemplate tenantJdbc, CanonicalOutboxPort tenantCanonicalOutbox) {
        return new JdbcBusinessTraceabilityAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"), mapper,
                Objects.requireNonNull(tenantCanonicalOutbox, "Tenant canonical outbox port is required"));
    }
}
