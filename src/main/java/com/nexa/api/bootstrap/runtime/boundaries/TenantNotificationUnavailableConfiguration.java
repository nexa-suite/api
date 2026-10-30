package com.nexa.api.bootstrap.runtime.boundaries;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Prevents API notification requests from selecting shared central persistence while Tenant routing is disabled. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.notifications", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class TenantNotificationUnavailableConfiguration {
    @Bean
    @Primary
    TenantNotificationUnavailableAdapter tenantNotificationUnavailableAdapter() {
        return new TenantNotificationUnavailableAdapter();
    }
}
