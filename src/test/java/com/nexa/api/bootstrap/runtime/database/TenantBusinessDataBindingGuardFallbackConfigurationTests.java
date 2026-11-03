package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.shared.application.port.out.TenantBusinessDataBindingGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantBusinessDataBindingGuardFallbackConfigurationTests {
	@Test
	void nonLocalFallbackReadsTenantBindingsFromTheCentralRegistry() {
		JdbcTemplate centralJdbc = mock(JdbcTemplate.class);
		UUID tenantId = UUID.randomUUID();
		when(centralJdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantId))).thenReturn(true);
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		beanFactory.registerSingleton("centralJdbc", centralJdbc);

		TenantBusinessDataBindingGuard guard = new TenantBusinessDataBindingGuardFallbackConfiguration()
				.tenantBusinessDataBindingGuard(beanFactory.getBeanProvider(JdbcTemplate.class));

		assertThat(guard).isInstanceOf(JdbcTenantBusinessDatabaseBindingRegistry.class);
		RlsRequestScope.set(tenantId, UUID.randomUUID());
		try {
			assertThat(guard.hasAnyBinding(tenantId)).isTrue();
		} finally {
			RlsRequestScope.clear();
		}
	}

	@Test
	void missingCentralJdbcFailsClosed() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		TenantBusinessDataBindingGuard guard = new TenantBusinessDataBindingGuardFallbackConfiguration()
				.tenantBusinessDataBindingGuard(beanFactory.getBeanProvider(JdbcTemplate.class));

		assertThat(guard.hasAnyBinding(UUID.randomUUID())).isTrue();
	}
}
