package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.springframework.core.env.Environment;

import java.util.UUID;

/** Resolves only one explicitly configured cloud binding; unknown references never fall back. */
final class CloudTenantDatabaseEnvironmentCredentials implements TenantBusinessDatabaseCredentialsProvider,
		TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider {
	private static final String PREFIX = "nexa.tenant-business.cloud-router.environment.";
	private final String reference;
	private final TenantId tenantId;
	private final UUID databaseIdentity;
	private final TenantBusinessDatabaseCredentials runtime;
	private final TenantBusinessDatabaseCredentials writer;

	CloudTenantDatabaseEnvironmentCredentials(Environment environment) {
		try {
			reference = required(environment, "credential-secret-reference");
			if (!reference.matches("cloud-tenant-db:[a-z0-9][a-z0-9_-]{31,127}")) {
				throw CloudTenantDatabaseSecretFiles.unavailable();
			}
			tenantId = new TenantId(required(environment, "tenant-id"));
			databaseIdentity = UUID.fromString(required(environment, "database-identity"));
			String jdbcUrl = required(environment, "jdbc-url");
			CloudTenantDatabaseSecretFiles.requireNeonJdbcUrl(jdbcUrl);
			String runtimeUser = required(environment, "runtime-username");
			String writerUser = required(environment, "policy-writer-username");
			String runtimePassword = required(environment, "runtime-password");
			String writerPassword = required(environment, "policy-writer-password");
			if (!"nexa_runtime".equals(runtimeUser) || !"nexa_policy_snapshot_writer".equals(writerUser)
					|| runtimePassword.equals(writerPassword)
					|| reusedCentralPassword(environment, runtimePassword)
					|| reusedCentralPassword(environment, writerPassword)) {
				throw CloudTenantDatabaseSecretFiles.unavailable();
			}
			runtime = new TenantBusinessDatabaseCredentials(jdbcUrl, runtimeUser, runtimePassword);
			writer = new TenantBusinessDatabaseCredentials(jdbcUrl, writerUser, writerPassword);
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw CloudTenantDatabaseSecretFiles.unavailable();
		}
	}

	@Override
	public TenantBusinessDatabaseCredentials requireCredentials(String credentialReference) {
		if (!reference.equals(credentialReference)) throw CloudTenantDatabaseSecretFiles.unavailable();
		return runtime;
	}

	@Override
	public TenantBusinessDatabaseCredentials requireWriterCredentials(TenantId requestedTenant, UUID requestedDatabase) {
		if (!tenantId.equals(requestedTenant) || !databaseIdentity.equals(requestedDatabase)) {
			throw new TenantBusinessDatabasePolicySnapshotWriterUnavailableException();
		}
		return writer;
	}

	@Override
	public String toString() {
		return "CloudTenantDatabaseEnvironmentCredentials[material=<redacted>]";
	}

	private static String required(Environment environment, String suffix) {
		String value = environment.getProperty(PREFIX + suffix);
		if (value == null || value.isBlank() || value.length() > 4_096) {
			throw CloudTenantDatabaseSecretFiles.unavailable();
		}
		return value;
	}

	private static boolean reusedCentralPassword(Environment environment, String password) {
		return password.equals(environment.getProperty("nexa.database.runtime-password"))
				|| password.equals(environment.getProperty("nexa.database.migrator-password"))
				|| password.equals(environment.getProperty("spring.datasource.password"))
				|| password.equals(environment.getProperty("NEXA_DATABASE_PASSWORD"))
				|| password.equals(environment.getProperty("NEXA_DATABASE_RUNTIME_PASSWORD"))
				|| password.equals(environment.getProperty("NEXA_DATABASE_MIGRATOR_PASSWORD"));
	}
}
