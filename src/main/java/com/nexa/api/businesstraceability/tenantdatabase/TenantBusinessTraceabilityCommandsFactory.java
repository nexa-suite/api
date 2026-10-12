package com.nexa.api.businesstraceability.tenantdatabase;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Binds BC-11 traceability writes to the JDBC session supplied by the explicit Tenant router callback.
 * This is a technical composition seam; application callers depend on {@link BusinessTraceabilityCommands}.
 */
public interface TenantBusinessTraceabilityCommandsFactory {
    BusinessTraceabilityCommands bindTo(JdbcTemplate tenantJdbc, CanonicalOutboxPort tenantCanonicalOutbox);
}
