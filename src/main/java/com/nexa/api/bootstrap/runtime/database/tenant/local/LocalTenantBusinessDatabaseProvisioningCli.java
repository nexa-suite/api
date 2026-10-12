package com.nexa.api.bootstrap.runtime.database.tenant.local;

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

	static void verifyResumableDatabase(Connection connection, UUID tenantId, UUID workspaceId,
			UUID databaseIdentity) throws SQLException {
		boolean historyExists = exists(connection,
				"SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
		boolean identityExists = exists(connection,
				"SELECT to_regclass('nexa_platform.tenant_business_database_identity') IS NOT NULL");
		if (!historyExists && !identityExists) {
			try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("""
					 SELECT EXISTS (
					     SELECT 1 FROM pg_namespace
					     WHERE nspname NOT IN ('pg_catalog', 'information_schema', 'public', 'pg_toast')
					       AND nspname !~ '^pg_(temp|toast_temp)_[0-9]+$'
					 ) OR EXISTS (
					     SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
					     WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f')
				     ) OR EXISTS (
				         SELECT 1 FROM pg_proc WHERE pronamespace='public'::regnamespace
				     ) OR EXISTS (
				         SELECT 1 FROM pg_type
				         WHERE typnamespace='public'::regnamespace AND typrelid=0 AND typtype <> 'p'
				     ) OR EXISTS (
				         SELECT 1 FROM pg_extension WHERE extnamespace='public'::regnamespace
					 )
					 """)) {
				result.next();
				if (result.getBoolean(1)) {
					throw new IllegalStateException("Tenant database is not empty and has no trusted migration identity");
				}
			}
			return;
		}
		if (!historyExists || !identityExists) {
			throw new IllegalStateException("Tenant database migration history and identity are inconsistent");
		}
		verifyPersistedIdentity(connection, tenantId, databaseIdentity, false);
		if (exists(connection, "SELECT to_regclass('nexa_platform.tenant_workspace_scope_anchor') IS NOT NULL")) {
			verifyPersistedWorkspace(connection, tenantId, workspaceId, false);
		}
	}

	private static void verifyIdentityAndWorkspace(Connection connection, UUID tenantId, UUID workspaceId,
			UUID databaseIdentity) throws SQLException {
		verifyPersistedIdentity(connection, tenantId, databaseIdentity, true);
		verifyPersistedWorkspace(connection, tenantId, workspaceId, true);
	}

	private static void verifyPersistedIdentity(Connection connection, UUID tenantId, UUID databaseIdentity,
			boolean requirePresent) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("""
				 SELECT tenant_id, database_identity
				 FROM nexa_platform.tenant_business_database_identity
				 WHERE singleton = TRUE
			 """)) {
			if (!result.next()) {
				if (requirePresent) throw new IllegalStateException("Tenant database identity is missing");
				return;
			}
			if (!tenantId.equals(result.getObject("tenant_id", UUID.class))
					|| !databaseIdentity.equals(result.getObject("database_identity", UUID.class))
					|| result.next()) {
				throw new IllegalStateException("Tenant database identity does not match its local binding");
			}
		}
	}

	private static void verifyPersistedWorkspace(Connection connection, UUID tenantId, UUID workspaceId,
			boolean requirePresent) throws SQLException {
		try (var statement = connection.prepareStatement("""
				 SELECT tenant_id, workspace_id
				 FROM nexa_platform.tenant_workspace_scope_anchor
				 WHERE tenant_id = ? AND workspace_id = ?
			 """)) {
			statement.setObject(1, tenantId);
			statement.setObject(2, workspaceId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					if (requirePresent) throw new IllegalStateException("Tenant Workspace scope anchor is missing");
					return;
				}
				if (!tenantId.equals(result.getObject("tenant_id", UUID.class))
						|| !workspaceId.equals(result.getObject("workspace_id", UUID.class))
						|| result.next()) {
					throw new IllegalStateException("Tenant Workspace scope anchor does not match its local binding");
				}
			}
		}
	}

	private static void verifyRuntimeRole(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("""
				 SELECT current_user,
				        runtime.rolcanlogin, runtime.rolsuper, runtime.rolcreatedb,
				        runtime.rolcreaterole, runtime.rolreplication, runtime.rolbypassrls,
				        migrator.rolcanlogin, migrator.rolsuper, migrator.rolcreatedb,
				        migrator.rolcreaterole, migrator.rolreplication, migrator.rolbypassrls,
				        (has_database_privilege('nexa_runtime', current_database(), 'CREATE')
				         OR has_database_privilege('nexa_runtime', current_database(), 'TEMPORARY')),
				        (EXISTS (SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba=runtime.oid)
				         OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=runtime.oid)
				         OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=runtime.oid)
				         OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=runtime.oid)
				         OR EXISTS (SELECT 1 FROM pg_namespace
				                   WHERE has_schema_privilege(runtime.oid, oid, 'CREATE'))),
				        has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'SELECT'),
				        (has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'INSERT')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'UPDATE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'DELETE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'TRUNCATE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'REFERENCES')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_business_database_identity', 'TRIGGER')),
				        has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'SELECT'),
				        (has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'INSERT')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'UPDATE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'DELETE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'TRUNCATE')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'REFERENCES')
				         OR has_table_privilege('nexa_runtime', 'nexa_platform.tenant_workspace_scope_anchor', 'TRIGGER')),
		        has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'SELECT'),
		        (has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'INSERT')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'UPDATE')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'DELETE')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRUNCATE')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'REFERENCES')
		                 OR has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRIGGER')),
				        EXISTS (SELECT 1 FROM pg_database
				                WHERE datname=current_database()
				                  AND datdba=(SELECT oid FROM pg_roles WHERE rolname='nexa_tenant_bootstrap_admin')),
				        EXISTS (SELECT 1 FROM pg_auth_members
				                WHERE member=(SELECT oid FROM pg_roles WHERE rolname='nexa_runtime')
				                   OR roleid=(SELECT oid FROM pg_roles WHERE rolname='nexa_runtime')),
				        EXISTS (SELECT 1 FROM pg_auth_members
				                WHERE member=(SELECT oid FROM pg_roles WHERE rolname='nexa_migrator')
				                   OR roleid=(SELECT oid FROM pg_roles WHERE rolname='nexa_migrator'))
				 FROM pg_roles runtime, pg_roles migrator
				 WHERE runtime.rolname='nexa_runtime' AND migrator.rolname='nexa_migrator'
			 """)) {
			if (!result.next()) throw new IllegalStateException("Tenant runtime and migrator roles are missing");
			if (!"nexa_runtime".equals(result.getString(1)) || !result.getBoolean(2)
					|| result.getBoolean(3) || result.getBoolean(4) || result.getBoolean(5)
					|| result.getBoolean(6) || result.getBoolean(7) || !result.getBoolean(8)
					|| result.getBoolean(9) || result.getBoolean(10) || result.getBoolean(11)
					|| result.getBoolean(12) || result.getBoolean(13) || result.getBoolean(14)
					|| result.getBoolean(15) || !result.getBoolean(16) || result.getBoolean(17)
				|| !result.getBoolean(18) || result.getBoolean(19) || !result.getBoolean(20)
				|| result.getBoolean(21) || !result.getBoolean(22) || result.getBoolean(23)
				|| result.getBoolean(24)) {
				throw new IllegalStateException("Tenant runtime or migrator role privileges are not least-privileged");
			}
		}
	}

	private static void verifyPolicySnapshotWriterRole(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("""
				 SELECT current_user,
				        writer.rolcanlogin, writer.rolsuper, writer.rolcreatedb,
				        writer.rolcreaterole, writer.rolreplication, writer.rolbypassrls,
				        (has_database_privilege('nexa_policy_snapshot_writer', current_database(), 'CREATE')
				         OR has_database_privilege('nexa_policy_snapshot_writer', current_database(), 'TEMPORARY')),
				        (EXISTS (SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba=writer.oid)
				         OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=writer.oid)
				         OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=writer.oid)
				         OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=writer.oid)
				         OR EXISTS (SELECT 1 FROM pg_namespace
				                   WHERE has_schema_privilege(writer.oid, oid, 'CREATE'))),
				        EXISTS (SELECT 1 FROM pg_auth_members
				                WHERE member=writer.oid OR roleid=writer.oid),
				        has_schema_privilege('nexa_policy_snapshot_writer', 'nexa_platform', 'USAGE'),
				        has_table_privilege('nexa_policy_snapshot_writer',
				                'nexa_platform.tenant_business_database_identity', 'SELECT'),
				        (has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'INSERT')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'UPDATE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'DELETE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'TRUNCATE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'REFERENCES')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_business_database_identity', 'TRIGGER')),
				        has_table_privilege('nexa_policy_snapshot_writer',
				                'nexa_platform.tenant_workspace_scope_anchor', 'SELECT'),
				        (has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'INSERT')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'UPDATE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'DELETE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'TRUNCATE')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'REFERENCES')
				         OR has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.tenant_workspace_scope_anchor', 'TRIGGER')),
				        has_table_privilege('nexa_policy_snapshot_writer',
				                'nexa_platform.purchase_request_expiry_policy_snapshot', 'SELECT'),
				        (has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'INSERT')
				         AND has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'UPDATE')
				         AND NOT has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'DELETE')
				         AND NOT has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRUNCATE')
				         AND NOT has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'REFERENCES')
				         AND NOT has_table_privilege('nexa_policy_snapshot_writer',
				                 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRIGGER')),
				        NOT EXISTS (
				            SELECT 1 FROM pg_class relation
				            JOIN pg_namespace namespace_row ON namespace_row.oid=relation.relnamespace
				            WHERE relation.relkind IN ('r','p','v','m','f')
				              AND namespace_row.nspname NOT IN ('pg_catalog','information_schema')
				              AND namespace_row.nspname || '.' || relation.relname NOT IN (
				                  'nexa_platform.tenant_business_database_identity',
				                  'nexa_platform.tenant_workspace_scope_anchor',
				                  'nexa_platform.purchase_request_expiry_policy_snapshot')
				              AND (has_table_privilege(writer.oid, relation.oid, 'SELECT')
				                   OR has_table_privilege(writer.oid, relation.oid, 'INSERT')
				                   OR has_table_privilege(writer.oid, relation.oid, 'UPDATE')
				                   OR has_table_privilege(writer.oid, relation.oid, 'DELETE')
				                   OR has_table_privilege(writer.oid, relation.oid, 'TRUNCATE')
				                   OR has_table_privilege(writer.oid, relation.oid, 'REFERENCES')
				                   OR has_table_privilege(writer.oid, relation.oid, 'TRIGGER'))
				        )
				        AND NOT EXISTS (
				            SELECT 1 FROM pg_class sequence_row
				            JOIN pg_namespace namespace_row ON namespace_row.oid=sequence_row.relnamespace
				            WHERE sequence_row.relkind='S'
				              AND namespace_row.nspname NOT IN ('pg_catalog','information_schema')
				              AND (has_sequence_privilege(writer.oid, sequence_row.oid, 'USAGE')
				                   OR has_sequence_privilege(writer.oid, sequence_row.oid, 'SELECT')
				                   OR has_sequence_privilege(writer.oid, sequence_row.oid, 'UPDATE'))
				        )
				        AND NOT EXISTS (
				            SELECT 1 FROM pg_namespace namespace_row
				            WHERE namespace_row.nspname NOT IN
				                    ('pg_catalog','information_schema','public','nexa_platform')
				              AND has_schema_privilege(writer.oid, namespace_row.oid, 'USAGE')
				        )
				 FROM pg_roles writer WHERE writer.rolname='nexa_policy_snapshot_writer'
				 """)) {
			if (!result.next()) throw new IllegalStateException("Tenant policy-snapshot writer role is missing");
			if (!"nexa_policy_snapshot_writer".equals(result.getString(1)) || !result.getBoolean(2)
					|| result.getBoolean(3) || result.getBoolean(4) || result.getBoolean(5)
					|| result.getBoolean(6) || result.getBoolean(7) || result.getBoolean(8)
					|| result.getBoolean(9) || result.getBoolean(10) || !result.getBoolean(11)
					|| !result.getBoolean(12) || result.getBoolean(13) || !result.getBoolean(14)
					|| result.getBoolean(15) || !result.getBoolean(16) || !result.getBoolean(17)
					|| !result.getBoolean(18)) {
				throw new IllegalStateException("Tenant policy-snapshot writer role privileges are not least-privileged");
			}
		}
	}

	private static boolean exists(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			result.next();
			return result.getBoolean(1);
		}
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
