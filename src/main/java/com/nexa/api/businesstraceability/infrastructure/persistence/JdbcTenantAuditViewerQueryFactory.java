package com.nexa.api.businesstraceability.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.port.out.AuditViewerQueryPort;
import com.nexa.api.businesstraceability.tenantdatabase.TenantAuditViewerQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Binds the existing BC-11 query adapter to one Tenant transaction. */
@Component
@Profile("!test")
public final class JdbcTenantAuditViewerQueryFactory implements TenantAuditViewerQueryFactory {
    private final ObjectMapper mapper;

    public JdbcTenantAuditViewerQueryFactory(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "Audit metadata mapper is required");
    }

    @Override
    public AuditViewerQueryPort bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcAuditViewerQueryAdapter(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"), mapper);
    }
}
