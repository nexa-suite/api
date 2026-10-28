package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Provides central Tenant binding checks in local mode, whether physical routing is enabled or not. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalTenantBusinessDatabaseBindingRegistryConfiguration {
    @Bean
    TenantBusinessDatabaseBindingRegistry localTenantBusinessDatabaseBindingRegistry(JdbcTemplate centralJdbc) {
        return new JdbcTenantBusinessDatabaseBindingRegistry(centralJdbc);
    }
}
