package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.edge.security.NexaSecurityProperties;
import com.nexa.api.edge.security.SecureRuntimeConfigurationValidator;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CloudTenantBusinessDatabaseRouterConfigurationTests {
	@TempDir Path directory;

	@Test
	void requiresBothCloudProfileAndExplicitOptIn() throws IOException {
		context().run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(TenantBusinessDatabaseRouter.class)
					.doesNotHaveBean(TenantBusinessDatabaseCredentialsProvider.class)
					.hasSingleBean(SecureRuntimeConfigurationValidator.class);
		});
		context().withPropertyValues("spring.profiles.active=s3", "nexa.tenant-business.cloud-router.enabled=true")
				.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TenantBusinessDatabaseRouter.class));
	}

	@Test
	void optsInWithoutOpeningTenantConnectionsOrChangingCentralSecurity() throws IOException {
		context().withPropertyValues("nexa.tenant-business.cloud-router.enabled=true").run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(TenantBusinessDatabaseRouter.class)
					.hasSingleBean(SecureRuntimeConfigurationValidator.class)
					.hasSingleBean(TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory.class);
			assertThat(context.getBean(TenantBusinessDatabaseAuthority.class)).isInstanceOf(JdbcTenantBusinessDatabaseAuthority.class);
			assertThat(context.getBean(TenantBusinessDatabaseBindingRegistry.class)).isInstanceOf(JdbcTenantBusinessDatabaseBindingRegistry.class);
			assertThat(context.getBean(TenantBusinessDatabaseCredentialsProvider.class)).isInstanceOf(CloudTenantBusinessDatabaseCredentialsProvider.class);
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			verify(jdbc).afterPropertiesSet();
			verifyNoMoreInteractions(jdbc);
			verifyNoInteractions(context.getBean(ResolveCurrentAccessContextUseCase.class));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "local", "test", "local-fixtures" })
	void refusesProfilesThatCouldDisableSecureRuntimeValidationOrEnableFixtures(String profile) throws IOException {
		context().withPropertyValues("spring.profiles.active=tenant-cloud," + profile)
				.run(context -> assertThat(context).hasFailed().getFailure()
						.hasRootCauseMessage("Cloud Tenant routing cannot use local, fixture or test profiles"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "buyer-wallet-read", "buyer-wallet-recharge", "purchase-request",
			"catalog-favorites", "catalog-management", "payments", "fulfillment-delivery", "warehouse-operations",
			"local-fixtures", "support-sales-order-read", "business-documents", "business-traceability" })
	void refusesBusinessCapabilitiesUntilCloudCompositionIsImplemented(String capability) throws IOException {
		context().withPropertyValues("nexa.tenant-business." + capability + ".enabled=true")
				.run(context -> assertThat(context).hasFailed().getFailure()
						.hasRootCauseMessage("Cloud Tenant business capabilities require cloud composition before activation"));
	}

	@Test
	void allowsExistingTenantNotificationCompositionAlongsideExplicitCloudRouter() throws IOException {
		context().withPropertyValues("nexa.tenant-business.cloud-router.enabled=true",
				"nexa.tenant-business.notifications.enabled=true")
				.run(context -> assertThat(context).hasNotFailed().hasSingleBean(TenantBusinessDatabaseRouter.class));
	}

	@Test
	void requiresTheCompleteExplicitReadCompositionAndAcceptsNoOtherCapabilities() throws IOException {
		context().withPropertyValues("nexa.tenant-business.catalog-detail-read.enabled=true")
				.run(context -> assertThat(context).hasFailed());
		context().withPropertyValues("nexa.tenant-business.cloud-read.enabled=true")
				.run(context -> assertThat(context).hasFailed());
		context().withPropertyValues("nexa.tenant-business.cloud-read.enabled=true",
				"nexa.tenant-business.catalog-detail-read.enabled=true")
				.run(context -> assertThat(context).hasFailed());
		context().withPropertyValues("nexa.tenant-business.cloud-read.enabled=true",
				"nexa.tenant-business.catalog-detail-read.enabled=true", "nexa.tenant-business.cloud-router.enabled=true")
				.run(context -> assertThat(context).hasNotFailed().hasSingleBean(TenantBusinessDatabaseRouter.class));
	}

	@Test
	void environmentSourceIsExplicitAndNeverFallsBackToMountedOrLocalCredentials() throws IOException {
		context().withPropertyValues("nexa.tenant-business.cloud-router.enabled=true",
				"nexa.tenant-business.cloud-router.credentials-source=unknown")
				.run(context -> assertThat(context).hasFailed());
		context().withPropertyValues("nexa.tenant-business.cloud-router.enabled=true",
				"nexa.tenant-business.cloud-router.credentials-source=environment")
				.run(context -> assertThat(context).hasFailed());
		var environment = CloudTenantDatabaseEnvironmentCredentialsTests.environment();
		context().withPropertyValues("nexa.tenant-business.cloud-router.enabled=true",
				"nexa.tenant-business.cloud-router.credentials-source=environment",
				"nexa.tenant-business.cloud-router.runtime-credentials-directory=",
				"nexa.tenant-business.cloud-router.policy-writer-credentials-directory=")
				.withInitializer(context -> context.getEnvironment().getPropertySources()
						.addFirst(environment.getPropertySources().get("mockProperties")))
				.run(context -> assertThat(context).hasNotFailed()
						.getBean(TenantBusinessDatabaseCredentialsProvider.class)
						.isInstanceOf(CloudTenantDatabaseEnvironmentCredentials.class));
	}

	@ParameterizedTest
	@ValueSource(strings = { "nexa.dev-bootstrap.enabled", "nexa.local-demo-fixtures.enabled",
			"NEXA_DEV_BOOTSTRAP_ENABLED", "NEXA_LOCAL_DEMO_FIXTURES_ENABLED" })
	void refusesDevelopmentBootstrapAndFixtures(String property) throws IOException {
		context().withPropertyValues(property + "=true")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void refusesCapabilityEnvironmentVariablesAndMissingPrivilegeValidationOrSecretDirectories() throws IOException {
		context().withPropertyValues("NEXA_TENANT_BUSINESS_PURCHASE_REQUEST_ENABLED=true")
				.run(context -> assertThat(context).hasFailed());
		context().withPropertyValues("nexa.tenant-business.cloud-router.enabled=true",
				"nexa.database.require-least-privilege-runtime=false")
				.run(context -> assertThat(context).hasFailed().getFailure()
						.hasRootCauseMessage("Cloud Tenant routing requires least-privilege runtime validation"));
		context().withPropertyValues("nexa.tenant-business.cloud-router.enabled=true",
				"nexa.tenant-business.cloud-router.policy-writer-credentials-directory=")
				.run(context -> assertThat(context).hasFailed().getFailure()
						.hasRootCauseMessage("Cloud Tenant database credentials are unavailable or unsafe"));
	}

	@Test
	void secureRuntimeValidatorStillRejectsEphemeralKeysInCloudProfile() throws IOException {
		NexaSecurityProperties insecure = securityProperties();
		insecure.setAllowEphemeralKeys(true);
		context(insecure)
				.run(context -> assertThat(context).hasFailed().getFailure()
						.hasRootCauseMessage("Invalid secure runtime configuration: ephemeral RSA keys are not allowed outside local/test"));
	}

	private ApplicationContextRunner context() throws IOException {
		return context(securityProperties());
	}

	private ApplicationContextRunner context(NexaSecurityProperties security) {
		return new ApplicationContextRunner()
				.withUserConfiguration(CloudTenantBusinessDatabaseRouterConfiguration.class, SecureRuntimeConfigurationValidator.class)
				.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
				.withBean(ResolveCurrentAccessContextUseCase.class, () -> mock(ResolveCurrentAccessContextUseCase.class))
				.withBean(NexaSecurityProperties.class, () -> security)
				.withPropertyValues("spring.profiles.active=tenant-cloud", "nexa.database.require-least-privilege-runtime=true",
						"nexa.tenant-business.cloud-router.runtime-credentials-directory=" + directory,
						"nexa.tenant-business.cloud-router.policy-writer-credentials-directory=" + directory);
	}

	private NexaSecurityProperties securityProperties() throws IOException {
		Path key = directory.resolve("test-key");
		Files.writeString(key, "test-only-regular-file");
		NexaSecurityProperties security = new NexaSecurityProperties();
		security.setIssuer("https://api.example.test");
		security.setAudience("nexa-tests");
		security.setRsaPublicKey(key.toString());
		security.setRsaPrivateKey(key.toString());
		return security;
	}
}
