package com.nexa.api.bootstrap.runtime.database.tenant.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL-backed checks for the local Tenant database provisioning path. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class LocalTenantBusinessDatabaseProvisioningCliIT {
	private static final String MIGRATOR_PASSWORD = "tenant-migrator-only-test-password";
	private static final String RUNTIME_PASSWORD = "tenant-runtime-only-test-password";
	private static final String POLICY_SNAPSHOT_WRITER_PASSWORD = "tenant-policy-snapshot-writer-test-password";
	private static final String DOCUMENTS_WORKER_PASSWORD = "tenant-documents-worker-test-password";
	private static final String PAYMENTS_WORKER_PASSWORD = "tenant-payments-worker-test-password";
	private static final String TRACEABILITY_WORKER_PASSWORD = "tenant-traceability-worker-test-password";
	private static final String RESERVATION_EXPIRY_WORKER_PASSWORD = "tenant-reservation-expiry-worker-test-password";

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
			statement.execute("CREATE ROLE nexa_migrator LOGIN NOINHERIT PASSWORD '" + MIGRATOR_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_runtime LOGIN NOINHERIT PASSWORD '" + RUNTIME_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_policy_snapshot_writer LOGIN PASSWORD '"
					+ POLICY_SNAPSHOT_WRITER_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_business_documents_worker LOGIN PASSWORD '"
					+ DOCUMENTS_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_payments_worker LOGIN PASSWORD '"
					+ PAYMENTS_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_business_traceability_worker LOGIN PASSWORD '"
					+ TRACEABILITY_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_warehouse_reservation_expiry_worker LOGIN PASSWORD '"
					+ RESERVATION_EXPIRY_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("GRANT CONNECT, CREATE ON DATABASE nexa_tenant_business TO nexa_migrator");
			statement.execute("GRANT CONNECT ON DATABASE nexa_tenant_business TO nexa_runtime, nexa_policy_snapshot_writer, "
					+ "nexa_business_documents_worker, nexa_payments_worker, nexa_business_traceability_worker, "
					+ "nexa_warehouse_reservation_expiry_worker");
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
						+ "\napi-jdbc-url=jdbc:postgresql://tenant-db-" + tenantId
						+ ":5432/nexa_tenant_business\nusername=nexa_runtime\npassword=" + RUNTIME_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("state.properties"), "tenant-id=" + tenantId
				+ "\nworkspace-id=" + workspaceId + "\ndatabase-identity=" + databaseIdentity
				+ "\ncredential-secret-reference=" + credentialReference + "\ndatabase-port=" + mappedPort + "\n");
		writePrivateFile(tenantDirectory.resolve("policy-snapshot-writer.properties"),
				"jdbc-url=" + jdbcUrl + "\napi-jdbc-url=jdbc:postgresql://tenant-db-" + tenantId
						+ ":5432/nexa_tenant_business\nusername=nexa_policy_snapshot_writer\npassword="
						+ POLICY_SNAPSHOT_WRITER_PASSWORD + "\n");

		migrateTenantDatabaseToBuyerAccountType(jdbcUrl);
		UUID secondWorkspaceId = UUID.randomUUID();
		UUID firstAccountId = UUID.randomUUID();
		UUID secondAccountId = UUID.randomUUID();
		UUID firstBuyerMembershipId = UUID.randomUUID();
		UUID secondBuyerMembershipId = UUID.randomUUID();
		seedIdentityAndScope(tenantId, workspaceId, databaseIdentity);
		seedWorkspaceScope(tenantId, secondWorkspaceId);
		seedLegacyIndividualBuyer(tenantId, workspaceId, firstAccountId, firstBuyerMembershipId);
		seedLegacyIndividualBuyer(tenantId, secondWorkspaceId, secondAccountId, secondBuyerMembershipId);

		assertThatCode(() -> LocalTenantBusinessDatabaseProvisioningCli.migrate(
				tenantDirectory, tenantId, workspaceId, databaseIdentity)).doesNotThrowAnyException();
		try (Connection connection = DriverManager.getConnection(
				DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
			Statement statement = connection.createStatement();
			var result = statement.executeQuery("SELECT version FROM public.flyway_schema_history "
					+ "WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString(1)).isEqualTo(TenantBusinessDatabaseMigrationRequirements.load()
					.requiredVersions().stream()
					.max(java.util.Comparator.comparingInt(Integer::parseInt)).orElseThrow());
		}
		assertLegacyMembershipBackfills(firstAccountId, secondAccountId);
		assertThatCode(() -> LocalTenantBusinessDatabaseProvisioningCli.verify(
				tenantDirectory, tenantId, workspaceId, databaseIdentity, credentialReference))
				.doesNotThrowAnyException();
		assertRoleInheritanceRejected(tenantDirectory, tenantId, workspaceId, databaseIdentity,
				credentialReference, "nexa_runtime");
		assertRoleInheritanceRejected(tenantDirectory, tenantId, workspaceId, databaseIdentity,
				credentialReference, "nexa_migrator");
	}

	private static void migrateTenantDatabaseToBuyerAccountType(String jdbcUrl) {
		Flyway.configure()
				.dataSource(jdbcUrl, "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/tenant-migration")
				.target(MigrationVersion.fromVersion("33"))
				.cleanDisabled(true)
				.load()
				.migrate();
	}

	private static void seedLegacyIndividualBuyer(UUID tenantId, UUID workspaceId,
			UUID clientAccountId, UUID buyerMembershipId) throws Exception {
		try (Connection connection = DriverManager.getConnection(
				DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
			PreparedStatement account = connection.prepareStatement("""
				INSERT INTO sales.client_account (
					id,tenant_id,workspace_id,code,business_name,commercial_name,segment,
					contact_person,contact_email,phone,delivery_profile,payment_condition,status,
					created_at,updated_at,account_type
				) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'INDIVIDUAL')
				""");
			PreparedStatement membership = connection.prepareStatement("""
				INSERT INTO sales.client_account_membership (
					client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at
				) VALUES (?,?,?,?,TIMESTAMPTZ '2025-01-02 03:04:05+00')
				""")) {
			account.setObject(1, clientAccountId);
			account.setObject(2, tenantId);
			account.setObject(3, workspaceId);
			account.setString(4, "MIG-" + clientAccountId.toString().substring(0, 12));
			account.setString(5, "Synthetic individual account");
			account.setString(6, "Synthetic account");
			account.setString(7, "TEST");
			account.setString(8, "Test Buyer");
			account.setString(9, "buyer@example.invalid");
			account.setString(10, "+51000000000");
			account.setString(11, "{}");
			account.setString(12, "prepaid");
			account.setString(13, "ACTIVE");
			account.setObject(14, java.time.OffsetDateTime.parse("2025-01-02T03:04:05Z"));
			account.setObject(15, java.time.OffsetDateTime.parse("2025-01-02T03:04:05Z"));
			account.executeUpdate();
			membership.setObject(1, clientAccountId);
			membership.setObject(2, buyerMembershipId);
			membership.setObject(3, tenantId);
			membership.setObject(4, workspaceId);
			membership.executeUpdate();
		}
	}

	private static void assertLegacyMembershipBackfills(UUID firstAccountId, UUID secondAccountId)
			throws Exception {
		try (Connection connection = DriverManager.getConnection(
				DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
			PreparedStatement statement = connection.prepareStatement("""
				SELECT count(*)
				  FROM sales.client_account_membership member
			  JOIN sales.client_account account
			    ON account.tenant_id=member.tenant_id
			   AND account.workspace_id=member.workspace_id
			   AND account.id=member.client_account_id
			 WHERE member.client_account_id IN (?,?)
			   AND member.updated_at=member.created_at
			   AND member.individual_account_id=member.client_account_id
			""")) {
			statement.setObject(1, firstAccountId);
			statement.setObject(2, secondAccountId);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getInt(1)).isEqualTo(2);
			}
		}
	}

	private static void assertRoleInheritanceRejected(Path tenantDirectory, UUID tenantId, UUID workspaceId,
			UUID databaseIdentity, String credentialReference, String roleName) throws Exception {
		try (Connection admin = DriverManager.getConnection(
				DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
			Statement statement = admin.createStatement()) {
			statement.execute("ALTER ROLE " + roleName + " INHERIT");
		}
		try {
			assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verify(
					tenantDirectory, tenantId, workspaceId, databaseIdentity, credentialReference))
					.isInstanceOf(IllegalStateException.class)
					.hasMessage("Tenant runtime or migrator role privileges are not least-privileged");
		} finally {
			try (Connection admin = DriverManager.getConnection(
					DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
				Statement statement = admin.createStatement()) {
				statement.execute("ALTER ROLE " + roleName + " NOINHERIT");
			}
		}
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

	private static void seedWorkspaceScope(UUID tenantId, UUID workspaceId) throws Exception {
		try (Connection connection = DriverManager.getConnection(
				DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
			PreparedStatement anchor = connection.prepareStatement("""
				INSERT INTO nexa_platform.tenant_workspace_scope_anchor(tenant_id, workspace_id)
				VALUES (?, ?)
				""")) {
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
