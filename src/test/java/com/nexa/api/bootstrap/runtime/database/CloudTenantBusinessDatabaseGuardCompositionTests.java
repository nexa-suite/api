package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.database.tenant.cloud.CloudTenantBusinessDatabaseRouterConfiguration;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.shared.application.port.out.TenantBusinessDataBindingGuard;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CloudTenantBusinessDatabaseGuardCompositionTests {
	@TempDir Path directory;

	@ParameterizedTest
	@CsvSource({ "false,false", "false,true", "true,false", "true,true" })
	void cloudBindingGuardResolvesWithEitherConfigurationOrderAndOptInState(boolean enabled, boolean cloudFirst) {
		new ApplicationContextRunner()
				.withUserConfiguration(cloudFirst
						? new Class<?>[] { CloudTenantBusinessDatabaseRouterConfiguration.class, TenantBusinessDataBindingGuardFallbackConfiguration.class }
						: new Class<?>[] { TenantBusinessDataBindingGuardFallbackConfiguration.class, CloudTenantBusinessDatabaseRouterConfiguration.class })
				.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
				.withBean(ResolveCurrentAccessContextUseCase.class, () -> mock(ResolveCurrentAccessContextUseCase.class))
				.withPropertyValues("spring.profiles.active=tenant-cloud", "nexa.database.require-least-privilege-runtime=true",
						"nexa.tenant-business.cloud-router.enabled=" + enabled,
						"nexa.tenant-business.cloud-router.runtime-credentials-directory=" + directory,
						"nexa.tenant-business.cloud-router.policy-writer-credentials-directory=" + directory)
				.run(context -> {
					assertThat(context).hasNotFailed();
					TenantBusinessDataBindingGuard guard = context.getBean(TenantBusinessDataBindingGuard.class);
					assertThat(guard).isInstanceOf(JdbcTenantBusinessDatabaseBindingRegistry.class);
					if (enabled) assertThat(guard).isSameAs(context.getBean("cloudTenantBusinessDatabaseBindingRegistry"));
					assertThat(context.getBean(TenantBusinessDatabaseBindingRegistry.class)).isSameAs(guard);
				});
	}
}
