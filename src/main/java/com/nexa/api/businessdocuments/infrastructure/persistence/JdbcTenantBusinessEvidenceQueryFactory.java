package com.nexa.api.businessdocuments.infrastructure.persistence;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-09's read-only evidence projection on the exact routed Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantBusinessEvidenceQueryFactory implements TenantBusinessEvidenceQueryFactory {
    @Override
    public BusinessEvidenceQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcBusinessEvidenceQuery(Objects.requireNonNull(tenantJdbc,
                "Tenant JDBC session is required"));
    }
}
