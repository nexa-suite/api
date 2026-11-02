package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.DriverManagerTenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Disabled-by-default cloud routing foundation; business capabilities are not yet cloud-wired. */
@Configuration(proxyBeanMethods = false)
@Profile("tenant-cloud")
public class CloudTenantBusinessDatabaseRouterConfiguration {
	private static final List<String> UNSUPPORTED_CAPABILITIES = List.of(
			"buyer-wallet-read", "buyer-wallet-recharge", "purchase-request", "catalog-detail-read",
			"catalog-favorites", "catalog-management", "payments", "fulfillment-delivery", "warehouse-operations",
			"local-fixtures", "support-sales-order-read", "business-documents", "business-traceability", "notifications");

	public CloudTenantBusinessDatabaseRouterConfiguration(Environment environment) {
		if (environment.acceptsProfiles(Profiles.of("local", "test", "local-fixtures"))) {
			throw new IllegalStateException("Cloud Tenant routing cannot use local, fixture or test profiles");
		}
		if (enabled(environment, "nexa.dev-bootstrap.enabled")
				|| enabled(environment, "nexa.local-demo-fixtures.enabled")) {
			throw new IllegalStateException("Cloud Tenant routing cannot enable development bootstrap or fixtures");
		}
		for (String capability : UNSUPPORTED_CAPABILITIES) {
			if (enabled(environment, "nexa.tenant-business." + capability + ".enabled")) {
				throw new IllegalStateException("Cloud Tenant business capabilities require cloud composition before activation");
			}
		}
		if (enabled(environment, "nexa.tenant-business.cloud-router.enabled")
				&& !environment.getProperty("nexa.database.require-least-privilege-runtime", Boolean.class, false)) {
			throw new IllegalStateException("Cloud Tenant routing requires least-privilege runtime validation");
		}
	}

	private static boolean enabled(Environment environment, String property) {
		return environment.getProperty(property, Boolean.class, false)
				|| environment.getProperty(property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT),
						Boolean.class, false);
	}

	@Configuration(proxyBeanMethods = false)
	@Profile("tenant-cloud")
	@ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-router", name = "enabled", havingValue = "true")
	static class EnabledRouter {
		@Bean
		@Primary
		TenantBusinessDatabaseBindingRegistry cloudTenantBusinessDatabaseBindingRegistry(JdbcTemplate centralJdbc) {
			return new JdbcTenantBusinessDatabaseBindingRegistry(centralJdbc);
		}

		@Bean
		TenantBusinessDatabaseAuthority cloudTenantBusinessDatabaseAuthority(
				ResolveCurrentAccessContextUseCase accessContexts, TenantBusinessDatabaseBindingRegistry bindings) {
			return new JdbcTenantBusinessDatabaseAuthority(accessContexts, bindings);
		}

		@Bean
		TenantBusinessDatabaseCredentialsProvider cloudTenantBusinessDatabaseCredentialsProvider(
				@Value("${nexa.tenant-business.cloud-router.runtime-credentials-directory:}") String directory) {
			return new CloudTenantBusinessDatabaseCredentialsProvider(requireDirectory(directory));
		}

		@Bean(destroyMethod = "close")
		TenantBusinessDatabaseRouter cloudTenantBusinessDatabaseRouter(TenantBusinessDatabaseAuthority authority,
				TenantBusinessDatabaseCredentialsProvider credentials,
				@Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maxCachedPools,
				@Value("${nexa.tenant-business.router.maximum-pool-size:4}") int maxPoolSize) {
			TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
			requirements.verifyRequiredSqlAssets();
			return new TenantBusinessDatabaseRouter(authority,
					new HikariTenantBusinessDatabaseDataSourceFactory(credentials, maxPoolSize), maxCachedPools,
					requirements.schemaManifestDigest());
		}

		@Bean
		TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory cloudTenantPolicySnapshotWriterDataSourceFactory(
				@Value("${nexa.tenant-business.cloud-router.policy-writer-credentials-directory:}") String directory) {
			return new DriverManagerTenantBusinessDatabasePolicySnapshotWriterDataSourceFactory(
					new CloudTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(requireDirectory(directory)));
		}

		private static Path requireDirectory(String directory) {
			if (directory == null || directory.isBlank() || !Path.of(directory).isAbsolute()) {
				throw CloudTenantDatabaseSecretFiles.unavailable();
			}
			return Path.of(directory);
		}
	}
}
