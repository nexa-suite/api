package com.nexa.api.bootstrap.runtime.database.tenant.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL-backed checks for the local Tenant database provisioning path. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class LocalTenantBusinessDatabaseProvisioningCliIT {
	private static final String MIGRATOR_PASSWORD = "tenant-migrator-only-test-password";
	private static final String RUNTIME_PASSWORD = "tenant-runtime-only-test-password";
	private static final String POLICY_SNAPSHOT_WRITER_PASSWORD = "tenant-policy-snapshot-writer-test-password";

	@Container
	private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa_tenant_business")
			.withUsername("nexa_tenant_bootstrap_admin")
			.withPassword("tenant-bootstrap-only-test-password");

	@Test
	void localProvisioningMigratesFreshDatabaseAndVerifiesRuntimeScope(@TempDir Path tempDirectory) throws Exception {
		try (Connection admin = DriverManager.getConnection(
				DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
			Statement statement = admin.createStatement()) {
			statement.execute("CREATE ROLE nexa_migrator LOGIN PASSWORD '" + MIGRATOR_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_runtime LOGIN PASSWORD '" + RUNTIME_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_policy_snapshot_writer LOGIN PASSWORD '"
					+ POLICY_SNAPSHOT_WRITER_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("GRANT CONNECT, CREATE ON DATABASE nexa_tenant_business TO nexa_migrator");
			statement.execute("GRANT CONNECT ON DATABASE nexa_tenant_business TO nexa_runtime");
			statement.execute("GRANT CONNECT ON DATABASE nexa_tenant_business TO nexa_policy_snapshot_writer");
			statement.execute("REVOKE CONNECT, TEMPORARY ON DATABASE nexa_tenant_business FROM PUBLIC");
			statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
			statement.execute("GRANT CREATE ON SCHEMA public TO nexa_migrator");
		}

		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		UUID databaseIdentity = UUID.randomUUID();
		String credentialReference = "local-tenant-db:" + UUID.randomUUID();
		Path tenantDirectory = tempDirectory.resolve(tenantId.toString());
		Files.createDirectory(tenantDirectory);
		Files.setPosixFilePermissions(tempDirectory, privateDirectoryPermissions());
		Files.setPosixFilePermissions(tenantDirectory, privateDirectoryPermissions());
		int mappedPort = DATABASE.getMappedPort(5432);
		String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + mappedPort + "/nexa_tenant_business";
		writePrivateFile(tenantDirectory.resolve("migrator.properties"),
				"jdbc-url=" + jdbcUrl + "\nusername=nexa_migrator\npassword=" + MIGRATOR_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("credentials.properties"),
				"credential-secret-reference=" + credentialReference + "\njdbc-url=" + jdbcUrl
						+ "\nusername=nexa_runtime\npassword=" + RUNTIME_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("state.properties"), "tenant-id=" + tenantId
				+ "\nworkspace-id=" + workspaceId + "\ndatabase-identity=" + databaseIdentity
				+ "\ncredential-secret-reference=" + credentialReference + "\ndatabase-port=" + mappedPort + "\n");
		writePrivateFile(tenantDirectory.resolve("policy-snapshot-writer.properties"),
				"jdbc-url=" + jdbcUrl + "\nusername=nexa_policy_snapshot_writer\npassword="
						+ POLICY_SNAPSHOT_WRITER_PASSWORD + "\n");

		assertThatCode(() -> LocalTenantBusinessDatabaseProvisioningCli.migrate(
				tenantDirectory, tenantId, workspaceId, databaseIdentity)).doesNotThrowAnyException();
		try (Connection connection = DriverManager.getConnection(
				DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
			Statement statement = connection.createStatement();
			var result = statement.executeQuery("SELECT version FROM public.flyway_schema_history "
					+ "WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString(1)).isEqualTo("8");
		}
		seedIdentityAndScope(tenantId, workspaceId, databaseIdentity);
		assertThatCode(() -> LocalTenantBusinessDatabaseProvisioningCli.verify(
				tenantDirectory, tenantId, workspaceId, databaseIdentity, credentialReference))
				.doesNotThrowAnyException();
	}

	private static void seedIdentityAndScope(UUID tenantId, UUID workspaceId, UUID databaseIdentity) throws Exception {
		try (Connection connection = DriverManager.getConnection(
				DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
			PreparedStatement identity = connection.prepareStatement("""
				INSERT INTO nexa_platform.tenant_business_database_identity(singleton, tenant_id, database_identity)
				VALUES (TRUE, ?, ?)
				""");
			PreparedStatement anchor = connection.prepareStatement("""
				INSERT INTO nexa_platform.tenant_workspace_scope_anchor(tenant_id, workspace_id)
				VALUES (?, ?)
				""")) {
			identity.setObject(1, tenantId);
			identity.setObject(2, databaseIdentity);
			identity.executeUpdate();
			anchor.setObject(1, tenantId);
			anchor.setObject(2, workspaceId);
			anchor.executeUpdate();
		}
	}

	private static void writePrivateFile(Path path, String content) throws Exception {
		Files.writeString(path, content);
		Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
	}

	private static Set<PosixFilePermission> privateDirectoryPermissions() {
		return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_EXECUTE);
	}
}
