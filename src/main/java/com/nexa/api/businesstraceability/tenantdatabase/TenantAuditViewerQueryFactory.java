package com.nexa.api.businesstraceability.tenantdatabase;

import com.nexa.api.businesstraceability.application.port.out.AuditViewerQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-11 business-history reads to the exact routed Tenant JDBC session. */
@FunctionalInterface
public interface TenantAuditViewerQueryFactory {
    AuditViewerQueryPort bindTo(JdbcTemplate tenantJdbc);
}
