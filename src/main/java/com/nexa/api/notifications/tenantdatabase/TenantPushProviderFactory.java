package com.nexa.api.notifications.tenantdatabase;

import com.nexa.api.notifications.application.port.out.PushProviderPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Creates provider adapters bound to the currently routed Tenant database session. */
@FunctionalInterface
public interface TenantPushProviderFactory {
    PushProviderPort bindTo(JdbcTemplate tenantJdbc);
}
