package com.nexa.api.bootstrap.runtime.boundaries.payments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Keeps payment HTTP and webhook requests off central business persistence while Tenant routing is disabled. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class TenantPaymentUnavailableConfiguration {
    @Bean
    @Primary
    TenantPaymentUnavailableAdapter tenantPaymentUnavailableAdapter() {
        return new TenantPaymentUnavailableAdapter();
    }
}
