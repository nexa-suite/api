package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.shared.application.port.out.TenantBusinessDataBindingGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads Tenant bindings for central workers while physical routing remains unavailable. */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@ConditionalOnMissingBean(TenantBusinessDataBindingGuard.class)
class TenantBusinessDataBindingGuardFallbackConfiguration {
	@Bean
	TenantBusinessDataBindingGuard tenantBusinessDataBindingGuard(ObjectProvider<JdbcTemplate> centralJdbc) {
		JdbcTemplate jdbc = centralJdbc.getIfAvailable();
		return jdbc == null ? new TenantBusinessDataBindingGuard() { }
				: new JdbcTenantBusinessDatabaseBindingRegistry(jdbc);
	}
}
