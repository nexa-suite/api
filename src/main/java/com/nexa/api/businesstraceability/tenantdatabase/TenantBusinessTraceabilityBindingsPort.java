package com.nexa.api.businesstraceability.tenantdatabase;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Technical seam for binding BC-11 commands and the canonical outbox to one Tenant JDBC session. */
public interface TenantBusinessTraceabilityBindingsPort {
    Bindings bindTo(JdbcTemplate tenantJdbc);

    interface Bindings {
        BusinessTraceabilityCommands commands();

        CanonicalOutboxPort canonicalOutbox();
    }
}
