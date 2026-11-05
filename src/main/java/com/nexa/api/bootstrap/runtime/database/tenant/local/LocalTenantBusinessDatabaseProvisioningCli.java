package com.nexa.api.bootstrap.runtime.database.tenant.local;

import static com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseReadinessVerifier.verifyResumableDatabase;
import static com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseReadinessVerifier.verifyIdentityAndWorkspace;
import static com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseReadinessVerifier.verifyRuntimeRole;
import static com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseReadinessVerifier.verifyPolicySnapshotWriterRole;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local-only Flyway and readiness checks used by the opt-in Tenant database provisioner. */
public final class LocalTenantBusinessDatabaseProvisioningCli {
	private static final String DATABASE_NAME = "nexa_tenant_business";
	private static final String MIGRATOR_ROLE = "nexa_migrator";

	private LocalTenantBusinessDatabaseProvisioningCli() { }

	public static void main(String[] args) {
		try {
			Map<String, String> arguments = parseArguments(args);
			String mode = required(arguments, "mode");
			if ("manifest-digest".equals(mode)) {
				System.out.println(TenantBusinessDatabaseMigrationRequirements.load().schemaManifestDigest());
				return;
			}
			UUID tenantId = UUID.fromString(required(arguments, "tenant-id"));
			UUID workspaceId = UUID.fromString(required(arguments, "workspace-id"));
			UUID databaseIdentity = UUID.fromString(required(arguments, "database-identity"));
			Path tenantDirectory = Path.of(requiredEnvironment("NEXA_LOCAL_TENANT_DATABASE_HOME"));
			switch (mode) {
				case "migrate" -> migrate(tenantDirectory, tenantId, workspaceId, databaseIdentity);
				case "verify" -> verify(tenantDirectory, tenantId, workspaceId, databaseIdentity,
						requiredEnvironment("NEXA_LOCAL_TENANT_CREDENTIAL_REFERENCE"));
				default -> throw new IllegalArgumentException("Unsupported local Tenant database operation");
			}
		} catch (Exception exception) {
			if (exception instanceof SafeReadinessException) {
				System.err.println(exception.getMessage());
			} else {
				System.err.println("Local Tenant database operation failed (" + exception.getClass().getSimpleName()
						+ ")" + safeSqlState(exception)
						+ "; diagnostic details are withheld to keep credentials out of logs.");
			}
			System.exit(1);
		}
	}

	private static String safeSqlState(Throwable failure) {
		Throwable current = failure;
		for (int depth = 0; current != null && depth < 32; depth++, current = current.getCause()) {
			if (current instanceof SQLException sqlException) {
				String sqlState = sqlException.getSQLState();
				if (sqlState != null && sqlState.matches("[0-9A-Z]{5}")) {
					return "; SQLSTATE " + sqlState + ", cause chain " + safeCauseTypes(failure);
				}
			}
		}
		return "";
	}

	private static String safeCauseTypes(Throwable failure) {
		StringBuilder types = new StringBuilder();
		Throwable current = failure;
		for (int depth = 0; current != null && depth < 6; depth++, current = current.getCause()) {
			if (!types.isEmpty()) types.append(" -> ");
			types.append(current.getClass().getSimpleName());
		}
		return types.toString();
	}

	static void migrate(Path tenantDirectory, UUID tenantId, UUID workspaceId, UUID databaseIdentity)
			throws Exception {
		requireLocalTenantDirectory(tenantDirectory, tenantId);
		TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
		requirements.verifyRequiredSqlAssets();
		Map<String, String> migrator = privateCredentialFile(tenantDirectory.resolve("migrator.properties"));
		if (!MIGRATOR_ROLE.equals(migrator.get("username"))) {
			throw new IllegalStateException("Tenant migrations must use the dedicated migrator role");
		}
		String jdbcUrl = migrator.get("jdbc-url");
		LocalTenantBusinessDatabaseCredentialsProvider.requireLoopbackJdbcUrl(jdbcUrl);
		try (Connection connection = DriverManager.getConnection(jdbcUrl, MIGRATOR_ROLE, migrator.get("password"))) {
			verifyResumableDatabase(connection, tenantId, workspaceId, databaseIdentity);
		}
		Flyway flyway = Flyway.configure()
				.dataSource(jdbcUrl, MIGRATOR_ROLE, migrator.get("password"))
				.locations("classpath:db/tenant-migration")
				.cleanDisabled(true)
				.load();
		flyway.migrate();
		flyway.validate();
		verifyRequiredMigrations(flyway, requirements);
		System.out.println("Tenant business database migrations completed.");
	}

	private static void verifyRequiredMigrations(Flyway flyway,
			TenantBusinessDatabaseMigrationRequirements requirements) {
		Set<String> appliedVersions = new java.util.HashSet<>();
		for (MigrationInfo migration : flyway.info().applied()) {
			if (migration.getVersion() != null && migration.getState() == MigrationState.SUCCESS) {
				appliedVersions.add(migration.getVersion().getVersion());
			}
		}
		requirements.verifyRequiredMigrations(appliedVersions);
	}

	static void verifyRequiredMigrations(Set<String> appliedVersions) {
		TenantBusinessDatabaseMigrationRequirements.load().verifyRequiredMigrations(appliedVersions);
	}

	static void verify(Path tenantDirectory, UUID tenantId, UUID workspaceId, UUID databaseIdentity,
			String credentialReference) throws Exception {
		requireLocalTenantDirectory(tenantDirectory, tenantId);
		TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
		requirements.verifyRequiredSqlAssets();
		Map<String, String> migrator = privateCredentialFile(tenantDirectory.resolve("migrator.properties"));
		if (!MIGRATOR_ROLE.equals(migrator.get("username"))) {
			throw new SafeReadinessException("Tenant migrations must use the dedicated migrator role");
		}
		String migratorJdbcUrl = migrator.get("jdbc-url");
		LocalTenantBusinessDatabaseCredentialsProvider.requireLoopbackJdbcUrl(migratorJdbcUrl);
		Flyway flyway = Flyway.configure()
				.dataSource(migratorJdbcUrl, MIGRATOR_ROLE, migrator.get("password"))
				.locations("classpath:db/tenant-migration")
				.cleanDisabled(true)
				.load();
		flyway.validate();
		verifyRequiredMigrations(flyway, requirements);

		Path registryDirectory = tenantDirectory.getParent();
		LocalTenantBusinessDatabaseCredentialsProvider provider =
				new LocalTenantBusinessDatabaseCredentialsProvider(registryDirectory);
		var credentials = provider.requireHostCredentials(credentialReference);
		try (Connection connection = DriverManager.getConnection(credentials.jdbcUrl(), credentials.username(),
				credentials.password())) {
			if (!"nexa_runtime".equals(connection.getMetaData().getUserName())
					|| !DATABASE_NAME.equals(connection.getCatalog())) {
				throw new IllegalStateException("Tenant runtime connection reached an unexpected database identity");
			}
			verifyRuntimeRole(connection);
			verifyIdentityAndWorkspace(connection, tenantId, workspaceId, databaseIdentity);
		}
		LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider writerProvider =
				new LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(registryDirectory);
		var writerCredentials = writerProvider.requireHostWriterCredentials(new TenantId(tenantId), databaseIdentity);
		try (Connection connection = DriverManager.getConnection(writerCredentials.jdbcUrl(),
				writerCredentials.username(), writerCredentials.password())) {
			if (!"nexa_policy_snapshot_writer".equals(connection.getMetaData().getUserName())
					|| !DATABASE_NAME.equals(connection.getCatalog())) {
				throw new IllegalStateException("Tenant policy-snapshot writer reached an unexpected database identity");
			}
			verifyPolicySnapshotWriterRole(connection);
			verifyIdentityAndWorkspace(connection, tenantId, workspaceId, databaseIdentity);
		}
		System.out.println("Tenant identity, Workspace anchor, runtime restrictions, and dedicated writer role verified.");
	}

	private static Map<String, String> privateCredentialFile(Path file) throws Exception {
		Map<String, String> values = LocalTenantBusinessDatabaseCredentialsProvider.readPrivateProperties(file);
		if (!Set.of("jdbc-url", "username", "password").equals(values.keySet())) {
			throw new IllegalStateException("Local Tenant migrator credential file has an unexpected format");
		}
		return values;
	}

	private static void requireLocalTenantDirectory(Path directory, UUID tenantId) throws Exception {
		LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(directory);
		if (!tenantId.toString().equals(directory.getFileName().toString())) {
			throw new IllegalStateException("Local Tenant directory does not match the supplied Tenant id");
		}
	}

	private static Map<String, String> parseArguments(String[] args) {
		Map<String, String> arguments = new HashMap<>();
		for (String argument : args) {
			if (!argument.startsWith("--") || !argument.contains("=")) {
				throw new IllegalArgumentException("Local Tenant database arguments must use --key=value format");
			}
			int separator = argument.indexOf('=');
			String key = argument.substring(2, separator);
			String value = argument.substring(separator + 1);
			if (value.isBlank() || arguments.putIfAbsent(key, value) != null) {
				throw new IllegalArgumentException("Local Tenant database arguments are incomplete or duplicated");
			}
		}
		return Map.copyOf(arguments);
	}

	private static String required(Map<String, String> arguments, String key) {
		String value = arguments.get(key);
		if (value == null) throw new IllegalArgumentException("Required local Tenant database argument is missing");
		return value;
	}

	private static String requiredEnvironment(String key) {
		String value = System.getenv(key);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Required local Tenant configuration is missing");
		return value;
	}

	static final class SafeReadinessException extends IllegalStateException {
		SafeReadinessException(String message) {
			super(message);
		}
	}

}
