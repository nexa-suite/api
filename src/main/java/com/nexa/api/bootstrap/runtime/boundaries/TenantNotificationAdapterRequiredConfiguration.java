package com.nexa.api.bootstrap.runtime.boundaries;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Prevents enabled Tenant notification routes from starting without their Tenant persistence adapter. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.notifications", name = "enabled", havingValue = "true")
public class TenantNotificationAdapterRequiredConfiguration {
    public TenantNotificationAdapterRequiredConfiguration(TenantBoundNotificationCommands tenantNotifications) {
        if (tenantNotifications == null) throw new IllegalStateException("Tenant notification adapter is required");
    }
}
