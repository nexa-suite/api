package com.nexa.api.bootstrap.runtime.boundaries;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Provides the bootstrap-owned guard used by central fallback entrypoints. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
class CentralTenantBusinessDataAccessGuardConfiguration {
    @Bean
    CentralTenantBusinessDataAccessGuard centralTenantBusinessDataAccessGuard() {
        return new CentralTenantBusinessDataAccessGuard();
    }
}
