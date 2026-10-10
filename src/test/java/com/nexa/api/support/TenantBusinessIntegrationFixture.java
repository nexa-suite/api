package com.nexa.api.support;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/** Real, test-only Tenant PostgreSQL databases routed through the application binding registry. */
final class TenantBusinessIntegrationFixture {
	private static final String DATABASE_TEMPLATE = "nexa_tenant_template";
	private static final String ADMIN_USERNAME = "nexa_tenant_admin";
	private static final String ADMIN_PASSWORD = "test-only-tenant-admin-password";
	private static final String MIGRATOR_USERNAME = "nexa_tenant_migrator";
	private static final String MIGRATOR_PASSWORD = "test-only-tenant-migrator-password";
	private static final String TEST_USERNAME = "nexa_tenant_test";
	private static final String TEST_PASSWORD = "test-only-tenant-test-password";
	private static final String RUNTIME_USERNAME = "nexa_runtime";
	private static final String RUNTIME_PASSWORD = "test-only-tenant-runtime-password";
	private static final String POLICY_WRITER_USERNAME = "nexa_policy_snapshot_writer";
	private static final String POLICY_WRITER_PASSWORD = "test-only-policy-writer-password";
	private static final String DOCUMENTS_WORKER_USERNAME = "nexa_business_documents_worker";
	private static final String DOCUMENTS_WORKER_PASSWORD = "test-only-documents-worker-password";
	private static final String PAYMENTS_WORKER_USERNAME = "nexa_payments_worker";
	private static final String PAYMENTS_WORKER_PASSWORD = "test-only-payments-worker-password";
	private static final String TRACEABILITY_WORKER_USERNAME = "nexa_business_traceability_worker";
	private static final String TRACEABILITY_WORKER_PASSWORD = "test-only-traceability-worker-password";
	private static final String CREDENTIAL_REFERENCE_PREFIX = "test-tenant-business:";
	private static final PostgreSQLContainer TENANT_POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName(DATABASE_TEMPLATE)
			.withUsername(ADMIN_USERNAME)
			.withPassword(ADMIN_PASSWORD);
	private static final ConcurrentMap<UUID, String> DATABASE_NAMES = new ConcurrentHashMap<>();
	private static boolean started;

	private TenantBusinessIntegrationFixture() { }

	static synchronized void provision(JdbcTemplate centralJdbc, UUID tenantId, UUID workspaceId) {
		if (centralJdbc == null || tenantId == null || workspaceId == null) {
			throw new IllegalArgumentException("Central JDBC and exact Tenant/Workspace scope are required");
		}
		ensureStarted();
		requireCentralWorkspace(centralJdbc, tenantId, workspaceId);
		String databaseName = databaseName(tenantId);
		createTenantDatabaseIfMissing(databaseName);
		seedPhysicalIdentity(databaseName, tenantId, workspaceId);
		registerReadyBinding(centralJdbc, tenantId);
		DATABASE_NAMES.put(tenantId, databaseName);
	}

	static JdbcTemplate jdbcFor(UUID tenantId) {
		if (tenantId == null) throw new IllegalArgumentException("Exact Tenant id is required");
		String databaseName = DATABASE_NAMES.get(tenantId);
		if (databaseName == null) {
			throw new IllegalStateException("Tenant database has not been provisioned for this test scope");
		}
		return new JdbcTemplate(dataSource(jdbcUrl(databaseName), TEST_USERNAME, TEST_PASSWORD));
	}

	static TenantBusinessDatabaseCredentials credentials(String reference) {
		UUID tenantId = tenantIdFromReference(reference);
		String databaseName = DATABASE_NAMES.get(tenantId);
		if (databaseName == null) {
			throw new IllegalStateException("Tenant database credential reference is not provisioned");
		}
		return new TenantBusinessDatabaseCredentials(jdbcUrl(databaseName), RUNTIME_USERNAME, RUNTIME_PASSWORD);
	}

	static DataSource policyWriterDataSource(TenantBusinessDatabaseBinding binding) {
		if (binding == null) throw new IllegalArgumentException("Tenant database binding is required");
		UUID tenantId = binding.tenantId().value();
		String databaseName = DATABASE_NAMES.get(tenantId);
		if (databaseName == null) {
			throw new IllegalStateException("Tenant policy-writer database is not provisioned");
		}
		return dataSource(jdbcUrl(databaseName), POLICY_WRITER_USERNAME, POLICY_WRITER_PASSWORD);
	}

	static String credentialReference(UUID tenantId) {
		return CREDENTIAL_REFERENCE_PREFIX + tenantId;
	}

	private static void ensureStarted() {
		if (!Boolean.getBoolean("nexa.integration.enabled")) {
			throw new IllegalStateException("Tenant integration databases require nexa.integration.enabled=true");
		}
		if (started) return;
		TENANT_POSTGRES.start();
		provisionRoles();
		Flyway flyway = Flyway.configure()
				.dataSource(jdbcUrl(DATABASE_TEMPLATE), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/tenant-migration")
				.load();
		flyway.migrate();
		TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
		requirements.verifyRequiredSqlAssets();
		Set<String> appliedVersions = Arrays.stream(flyway.info().applied())
				.map(MigrationInfo::getVersion)
				.filter(java.util.Objects::nonNull)
				.map(org.flywaydb.core.api.MigrationVersion::getVersion)
				.collect(Collectors.toSet());
		requirements.verifyRequiredMigrations(appliedVersions);
		grantTestRoleOnTemplate();
		started = true;
	}

	private static void provisionRoles() {
		try (Connection connection = DriverManager.getConnection(jdbcUrl(DATABASE_TEMPLATE), ADMIN_USERNAME, ADMIN_PASSWORD);
			 Statement statement = connection.createStatement()) {
			createRole(statement, MIGRATOR_USERNAME, MIGRATOR_PASSWORD, false, false);
			createRole(statement, TEST_USERNAME, TEST_PASSWORD, false, true);
			createRole(statement, RUNTIME_USERNAME, RUNTIME_PASSWORD, false, false);
			createRole(statement, POLICY_WRITER_USERNAME, POLICY_WRITER_PASSWORD, true, false);
			createRole(statement, DOCUMENTS_WORKER_USERNAME, DOCUMENTS_WORKER_PASSWORD, true, false);
			createRole(statement, PAYMENTS_WORKER_USERNAME, PAYMENTS_WORKER_PASSWORD, true, false);
			createRole(statement, TRACEABILITY_WORKER_USERNAME, TRACEABILITY_WORKER_PASSWORD, true, false);
			statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + DATABASE_TEMPLATE + "\" TO " + MIGRATOR_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + DATABASE_TEMPLATE + "\" TO " + TEST_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + DATABASE_TEMPLATE + "\" TO " + RUNTIME_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + DATABASE_TEMPLATE + "\" TO " + POLICY_WRITER_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + DATABASE_TEMPLATE + "\" TO " + DOCUMENTS_WORKER_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + DATABASE_TEMPLATE + "\" TO " + PAYMENTS_WORKER_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + DATABASE_TEMPLATE + "\" TO " + TRACEABILITY_WORKER_USERNAME);
			statement.execute("GRANT CREATE ON SCHEMA public TO " + MIGRATOR_USERNAME);
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not provision Tenant integration database roles", exception);
		}
	}

	private static void createRole(Statement statement, String role, String password, boolean noInherit, boolean bypassRls)
			throws SQLException {
		String inheritance = noInherit ? "NOINHERIT" : "INHERIT";
		String rowSecurity = bypassRls ? "BYPASSRLS" : "NOBYPASSRLS";
		statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + role
				+ "') THEN EXECUTE format('CREATE ROLE %I LOGIN " + inheritance
				+ " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION " + rowSecurity + " PASSWORD %L', '" + role
				+ "', '" + password + "'); END IF; END $$");
	}

	private static void createTenantDatabaseIfMissing(String databaseName) {
		try (Connection connection = DriverManager.getConnection(jdbcUrl("postgres"), ADMIN_USERNAME, ADMIN_PASSWORD);
			 Statement statement = connection.createStatement()) {
			try (var existing = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname=?")) {
				existing.setString(1, databaseName);
				try (var rows = existing.executeQuery()) {
					if (rows.next()) return;
				}
			}
			statement.execute("CREATE DATABASE \"" + databaseName + "\" TEMPLATE \"" + DATABASE_TEMPLATE + "\"");
			statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + databaseName + "\" TO " + MIGRATOR_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + databaseName + "\" TO " + TEST_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + databaseName + "\" TO " + RUNTIME_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + databaseName + "\" TO " + POLICY_WRITER_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + databaseName + "\" TO " + DOCUMENTS_WORKER_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + databaseName + "\" TO " + PAYMENTS_WORKER_USERNAME);
			statement.execute("GRANT CONNECT ON DATABASE \"" + databaseName + "\" TO " + TRACEABILITY_WORKER_USERNAME);
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not create the physical Tenant integration database", exception);
		}
	}

	private static void grantTestRoleOnTemplate() {
		try (Connection connection = DriverManager.getConnection(jdbcUrl(DATABASE_TEMPLATE), ADMIN_USERNAME, ADMIN_PASSWORD);
			 Statement statement = connection.createStatement()) {
			for (String schema : new String[]{"audit", "business_documents", "catalog_management", "integration", "logistics",
					"nexa_platform", "notifications", "payments", "reference_data", "sales", "tenant_management", "warehouse"}) {
				statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + TEST_USERNAME);
				statement.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA " + schema + " TO " + TEST_USERNAME);
				statement.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA " + schema + " TO " + TEST_USERNAME);
				statement.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA " + schema + " TO " + TEST_USERNAME);
			}
			statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATOR_USERNAME
					+ " GRANT ALL PRIVILEGES ON TABLES TO " + TEST_USERNAME);
			statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATOR_USERNAME
					+ " GRANT ALL PRIVILEGES ON SEQUENCES TO " + TEST_USERNAME);
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not grant Tenant integration test-database privileges", exception);
		}
	}

	private static void seedPhysicalIdentity(String databaseName, UUID tenantId, UUID workspaceId) {
		JdbcTemplate tenantAdmin = new JdbcTemplate(dataSource(jdbcUrl(databaseName), MIGRATOR_USERNAME, MIGRATOR_PASSWORD));
		UUID databaseIdentity = databaseIdentity(tenantId);
		tenantAdmin.update("INSERT INTO nexa_platform.tenant_business_database_identity "
				+ "(singleton,tenant_id,database_identity) VALUES (true,?,?) ON CONFLICT (singleton) DO NOTHING",
			tenantId, databaseIdentity);
		UUID[] identity = tenantAdmin.queryForObject("SELECT tenant_id,database_identity "
				+ "FROM nexa_platform.tenant_business_database_identity WHERE singleton=true",
				(result, row) -> new UUID[]{result.getObject("tenant_id", UUID.class),
						result.getObject("database_identity", UUID.class)});
		if (identity == null || !tenantId.equals(identity[0]) || !databaseIdentity.equals(identity[1])) {
			throw new IllegalStateException("Tenant integration database identity conflicts with its central scope");
		}
		tenantAdmin.update("INSERT INTO nexa_platform.tenant_workspace_scope_anchor (tenant_id,workspace_id) "
				+ "VALUES (?,?) ON CONFLICT (tenant_id,workspace_id) DO NOTHING", tenantId, workspaceId);
		Integer anchorCount = tenantAdmin.queryForObject("SELECT count(*) FROM nexa_platform.tenant_workspace_scope_anchor "
				+ "WHERE tenant_id=? AND workspace_id=?", Integer.class, tenantId, workspaceId);
		if (anchorCount == null || anchorCount != 1) {
			throw new IllegalStateException("Exact Tenant integration Workspace anchor could not be persisted");
		}
	}

	private static void registerReadyBinding(JdbcTemplate centralJdbc, UUID tenantId) {
		UUID databaseIdentity = databaseIdentity(tenantId);
		String credentialReference = credentialReference(tenantId);
		String manifestDigest = TenantBusinessDatabaseMigrationRequirements.load().schemaManifestDigest();
		centralJdbc.update("INSERT INTO tenant_management.tenant_business_database_binding "
				+ "(tenant_id,database_identity,credential_secret_reference,lifecycle_state,verified_schema_manifest_sha256) "
				+ "VALUES (?,?,?,'READY',?) ON CONFLICT (tenant_id) DO NOTHING",
			tenantId, databaseIdentity, credentialReference, manifestDigest);
		var binding = centralJdbc.query("SELECT database_identity,credential_secret_reference,lifecycle_state,"
				+ "verified_schema_manifest_sha256 FROM tenant_management.tenant_business_database_binding WHERE tenant_id=?",
			(result, row) -> new BindingState(result.getObject("database_identity", UUID.class),
					result.getString("credential_secret_reference"), result.getString("lifecycle_state"),
					result.getString("verified_schema_manifest_sha256")), tenantId);
		if (binding.size() != 1 || !databaseIdentity.equals(binding.getFirst().databaseIdentity())
				|| !credentialReference.equals(binding.getFirst().credentialReference())
				|| !"READY".equals(binding.getFirst().state())
				|| !manifestDigest.equals(binding.getFirst().manifestDigest())) {
			throw new IllegalStateException("Central Tenant integration binding conflicts with the verified fixture");
		}
	}

	private static void requireCentralWorkspace(JdbcTemplate centralJdbc, UUID tenantId, UUID workspaceId) {
		Integer rows = centralJdbc.queryForObject("SELECT count(*) FROM tenant_management.workspace "
				+ "WHERE tenant_id=? AND id=? AND status='ACTIVE'", Integer.class, tenantId, workspaceId);
		if (rows == null || rows != 1) {
			throw new IllegalArgumentException("An exact active central Tenant Workspace is required for the fixture");
		}
	}

	private static UUID tenantIdFromReference(String reference) {
		if (reference == null || !reference.startsWith(CREDENTIAL_REFERENCE_PREFIX)) {
			throw new IllegalArgumentException("Unknown Tenant integration credential reference");
		}
		try {
			UUID tenantId = UUID.fromString(reference.substring(CREDENTIAL_REFERENCE_PREFIX.length()));
			if (!credentialReference(tenantId).equals(reference)) {
				throw new IllegalArgumentException("Non-canonical Tenant integration credential reference");
			}
			return tenantId;
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid Tenant integration credential reference");
		}
	}

	private static String databaseName(UUID tenantId) {
		return "nexa_tenant_" + tenantId.toString().replace("-", "");
	}

	private static UUID databaseIdentity(UUID tenantId) {
		return UUID.nameUUIDFromBytes(("nexa-test-tenant-business:" + tenantId).getBytes(StandardCharsets.UTF_8));
	}

	private static String jdbcUrl(String databaseName) {
		if (!databaseName.matches("[a-z0-9_]{1,63}")) {
			throw new IllegalArgumentException("Tenant integration database name is invalid");
		}
		String base = TENANT_POSTGRES.getJdbcUrl();
		int pathStart = base.lastIndexOf('/');
		if (pathStart < 0) throw new IllegalStateException("Tenant integration JDBC URL is invalid");
		int queryStart = base.indexOf('?', pathStart);
		return base.substring(0, pathStart + 1) + databaseName
				+ (queryStart < 0 ? "" : base.substring(queryStart));
	}

	private static DataSource dataSource(String jdbcUrl, String username, String password) {
		DriverManagerDataSource source = new DriverManagerDataSource();
		source.setUrl(jdbcUrl);
		source.setUsername(username);
		source.setPassword(password);
		return source;
	}

	private record BindingState(UUID databaseIdentity, String credentialReference, String state, String manifestDigest) { }
}
