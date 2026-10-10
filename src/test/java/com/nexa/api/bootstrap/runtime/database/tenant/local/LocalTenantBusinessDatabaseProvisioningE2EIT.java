package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end local provisioner checks against disposable central and Tenant PostgreSQL databases. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class LocalTenantBusinessDatabaseProvisioningE2EIT {
	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path PROVISIONER = REPOSITORY_ROOT.resolve("ops/scripts/provision-local-tenant-database.sh");
	private static final Path TENANT_COMPOSE = REPOSITORY_ROOT.resolve("ops/compose/tenant-business.local.compose.yml");
	private static final String CENTRAL_PASSWORD = "tenant-e2e-central-only-test-password";
	private static final String BOOTSTRAP_PASSWORD = "tenant-e2e-bootstrap-only-test-password";
	private static final String MIGRATOR_PASSWORD = "tenant-e2e-migrator-only-test-password";
	private static final String RUNTIME_PASSWORD = "tenant-e2e-runtime-only-test-password";
	private static final String POLICY_SNAPSHOT_WRITER_PASSWORD = "tenant-e2e-policy-snapshot-writer-only-test-password";
	private static final String WRONG_RUNTIME_PASSWORD = "tenant-e2e-wrong-runtime-only-test-password";
	private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	@Container
	private static final PostgreSQLContainer CENTRAL = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa_central_e2e")
			.withUsername("nexa_central_e2e_admin")
			.withPassword(CENTRAL_PASSWORD);

	@Test
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	void centralBindingCasCoversReadyFailureRetryAndIdempotentResume(@TempDir Path temporaryDirectory) throws Exception {
		Files.setPosixFilePermissions(temporaryDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		migrateCentral();
		Path centralConfig = temporaryDirectory.resolve("central.config");
		writePrivateFile(centralConfig, "container-id=" + CENTRAL.getContainerId() + "\n");
		assertThat(Files.getPosixFilePermissions(centralConfig)).isEqualTo(PRIVATE_FILE_PERMISSIONS);

		UUID readyTenantId = UUID.randomUUID();
		UUID readyWorkspaceId = UUID.randomUUID();
		seedTenantAndWorkspace(readyTenantId, readyWorkspaceId);
		Path readyStateDirectory = temporaryDirectory.resolve("ready-state");
		int readyPort = availableLoopbackPort();
		String readyVolume = volumeName(readyTenantId);
		assertThat(volumeExists(readyVolume)).isFalse();
		try {
			ProcessResult initial = runProvisioner(temporaryDirectory, centralConfig, readyStateDirectory,
					readyTenantId, readyWorkspaceId, readyPort, false);
			assertProvisionSucceeded(initial);
			Binding ready = readBinding(readyTenantId);
			assertThat(ready.lifecycleState()).isEqualTo("READY");
			assertThat(ready.version()).isEqualTo(1);
			assertThat(ready.databaseIdentity()).isEqualTo(UUID.fromString(readPrivateValue(
					readyStateDirectory.resolve(readyTenantId.toString()).resolve("state.properties"), "database-identity")));
			assertThat(ready.credentialSecretReference()).isEqualTo(readPrivateValue(
					readyStateDirectory.resolve(readyTenantId.toString()).resolve("state.properties"),
					"credential-secret-reference"));
			assertThat(ready.credentialSecretReference()).matches(
					"local-tenant-db:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
			assertThat(ready.verifiedSchemaManifestSha256())
					.isEqualTo(TenantBusinessDatabaseMigrationRequirements.load().schemaManifestDigest());
			assertSeparateLocalPasswords(readyStateDirectory.resolve(readyTenantId.toString()));
			assertTenantScope(readyStateDirectory, readyTenantId, readyWorkspaceId, ready);
			assertThat(volumeExists(readyVolume)).isTrue();

			ProcessResult idempotent = runProvisioner(temporaryDirectory, centralConfig, readyStateDirectory,
					readyTenantId, readyWorkspaceId, readyPort, false);
			assertProvisionSucceeded(idempotent);
			assertThat(readBinding(readyTenantId)).isEqualTo(ready);
			assertThat(volumeExists(readyVolume)).isTrue();
		} finally {
			stopTenantDatabase(readyStateDirectory, readyTenantId);
		}

		UUID retryTenantId = UUID.randomUUID();
		UUID retryWorkspaceId = UUID.randomUUID();
		UUID retryDatabaseIdentity = UUID.randomUUID();
		String retryCredentialReference = "local-tenant-db:" + UUID.randomUUID();
		seedTenantAndWorkspace(retryTenantId, retryWorkspaceId);
		insertProvisioningBinding(retryTenantId, retryDatabaseIdentity, retryCredentialReference);
		Path retryStateDirectory = temporaryDirectory.resolve("retry-state");
		Files.createDirectory(retryStateDirectory);
		Files.setPosixFilePermissions(retryStateDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		int retryPort = availableLoopbackPort();
		Path retryTenantDirectory = createPreProvisionedState(retryStateDirectory, retryTenantId,
				retryWorkspaceId, retryDatabaseIdentity, retryCredentialReference, retryPort);
		String retryVolume = volumeName(retryTenantId);
		assertThat(volumeExists(retryVolume)).isFalse();
		try {
			ProcessResult failed = runProvisioner(temporaryDirectory, centralConfig, retryStateDirectory,
					retryTenantId, retryWorkspaceId, retryPort, false);
			assertThat(failed.exitCode()).isNotZero();
			assertThat(redact(failed.output())).contains("central binding was marked FAILED");
			assertNoTestCredentialsInOutput(failed.output());
			Binding failedBinding = readBinding(retryTenantId);
			assertThat(failedBinding.lifecycleState()).isEqualTo("FAILED");
			assertThat(failedBinding.version()).isEqualTo(2);
			assertThat(failedBinding.databaseIdentity()).isEqualTo(retryDatabaseIdentity);
			assertThat(failedBinding.credentialSecretReference()).isEqualTo(retryCredentialReference);
			assertThat(volumeExists(retryVolume)).isTrue();
			assertTenantSchemaWasApplied(retryPort, BOOTSTRAP_PASSWORD, retryTenantId,
					retryWorkspaceId, retryDatabaseIdentity);

			writePrivateFile(retryTenantDirectory.resolve("credentials.properties"), runtimeCredentials(
					retryPort, retryCredentialReference, RUNTIME_PASSWORD));
			ProcessResult retry = runProvisioner(temporaryDirectory, centralConfig, retryStateDirectory,
					retryTenantId, retryWorkspaceId, retryPort, true);
			assertProvisionSucceeded(retry);
			Binding retriedBinding = readBinding(retryTenantId);
			assertThat(retriedBinding.lifecycleState()).isEqualTo("READY");
			assertThat(retriedBinding.version()).isEqualTo(4);
			assertThat(retriedBinding.databaseIdentity()).isEqualTo(retryDatabaseIdentity);
			assertThat(retriedBinding.credentialSecretReference()).isEqualTo(retryCredentialReference);
			assertTenantScope(retryStateDirectory, retryTenantId, retryWorkspaceId, retriedBinding);
			assertThat(volumeExists(retryVolume)).isTrue();

			ProcessResult resumed = runProvisioner(temporaryDirectory, centralConfig, retryStateDirectory,
					retryTenantId, retryWorkspaceId, retryPort, false);
			assertProvisionSucceeded(resumed);
			assertThat(readBinding(retryTenantId)).isEqualTo(retriedBinding);
			assertThat(volumeExists(retryVolume)).isTrue();
		} finally {
			stopTenantDatabase(retryStateDirectory, retryTenantId);
		}
	}

	private static void migrateCentral() {
		Flyway.configure().dataSource(CENTRAL.getJdbcUrl(), CENTRAL.getUsername(), CENTRAL.getPassword())
				.locations("classpath:db/migration").target("152").load().migrate();
		try (Connection connection = centralConnection();			 PreparedStatement statement = connection.prepareStatement(
					"SELECT max(version::INTEGER) FROM flyway_schema_history WHERE success = TRUE");
				 ResultSet result = statement.executeQuery()) {
			if (!result.next() || result.getInt(1) != 152) {
				throw new IllegalStateException("The disposable central database did not reach V152");
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Could not verify disposable central migration version", exception);
		}
	}

	private static void seedTenantAndWorkspace(UUID tenantId, UUID workspaceId) throws Exception {
		OffsetDateTime now = OffsetDateTime.now().withNano(0);
		String tenantSlug = "tenant-e2e-" + tenantId.toString().replace("-", "");
		String workspaceSlug = "workspace-e2e-" + workspaceId.toString().replace("-", "");
		try (Connection connection = centralConnection();
				 PreparedStatement tenant = connection.prepareStatement("""
					 INSERT INTO tenant_management.tenant(id,name,slug,status,created_at,updated_at)
					 VALUES (?, 'Provisioning E2E fixture tenant', ?, 'ACTIVE', ?, ?)
					 """);
				 PreparedStatement workspace = connection.prepareStatement("""
					 INSERT INTO tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at)
					 VALUES (?, ?, 'Provisioning E2E fixture workspace', ?, 'ACTIVE', ?, ?)
					 """)) {
			tenant.setObject(1, tenantId);
			tenant.setString(2, tenantSlug);
			tenant.setObject(3, now);
			tenant.setObject(4, now);
			tenant.executeUpdate();
			workspace.setObject(1, workspaceId);
			workspace.setObject(2, tenantId);
			workspace.setString(3, workspaceSlug);
			workspace.setObject(4, now);
			workspace.setObject(5, now);
			workspace.executeUpdate();
		}
	}

	private static void insertProvisioningBinding(UUID tenantId, UUID databaseIdentity, String credentialReference)
			throws Exception {
		try (Connection connection = centralConnection();
				 PreparedStatement statement = connection.prepareStatement("""
					 INSERT INTO tenant_management.tenant_business_database_binding
					     (tenant_id, database_identity, credential_secret_reference, lifecycle_state)
					 VALUES (?, ?, ?, 'PROVISIONING')
					 """)) {
			statement.setObject(1, tenantId);
			statement.setObject(2, databaseIdentity);
			statement.setString(3, credentialReference);
			statement.executeUpdate();
		}
	}

	private static Binding readBinding(UUID tenantId) throws Exception {
		try (Connection connection = centralConnection();
				 PreparedStatement statement = connection.prepareStatement("""
						 SELECT database_identity, credential_secret_reference, lifecycle_state, version,
						        verified_schema_manifest_sha256
					 FROM tenant_management.tenant_business_database_binding WHERE tenant_id=?
					 """)) {
			statement.setObject(1, tenantId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("The central Tenant binding was not persisted");
				Binding binding = new Binding(result.getObject(1, UUID.class), result.getString(2),
						result.getString(3), result.getLong(4), result.getString(5));
				if (result.next()) throw new IllegalStateException("The central Tenant binding is not unique");
				return binding;
			}
		}
	}

	private static Path createPreProvisionedState(Path stateRoot, UUID tenantId, UUID workspaceId,
			UUID databaseIdentity, String credentialReference, int port) throws Exception {
		Path tenantDirectory = stateRoot.resolve(tenantId.toString());
		Files.createDirectory(tenantDirectory);
		Files.setPosixFilePermissions(tenantDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		writePrivateFile(tenantDirectory.resolve("bootstrap-admin-password"), BOOTSTRAP_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("migrator-password"), MIGRATOR_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("runtime-password"), RUNTIME_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("policy-snapshot-writer-password"),
				POLICY_SNAPSHOT_WRITER_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("migrator.properties"), "jdbc-url=" + jdbcUrl(port)
				+ "\nusername=nexa_migrator\npassword=" + MIGRATOR_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("credentials.properties"), runtimeCredentials(
				port, credentialReference, WRONG_RUNTIME_PASSWORD));
		writePrivateFile(tenantDirectory.resolve("policy-snapshot-writer.properties"),
				"jdbc-url=" + jdbcUrl(port) + "\nusername=nexa_policy_snapshot_writer\npassword="
						+ POLICY_SNAPSHOT_WRITER_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("state.properties"), "tenant-id=" + tenantId
				+ "\nworkspace-id=" + workspaceId + "\ndatabase-identity=" + databaseIdentity
				+ "\ncredential-secret-reference=" + credentialReference + "\ndatabase-port=" + port + "\n");
		writePrivateFile(tenantDirectory.resolve("compose.env"), "NEXA_TENANT_DATABASE_PORT=" + port
				+ "\nNEXA_TENANT_DATABASE_ID=" + tenantId
				+ "\nNEXA_TENANT_DATABASE_SECRET_DIR=" + tenantDirectory.toRealPath() + "\n");
		return tenantDirectory;
	}

	private static String runtimeCredentials(int port, String reference, String password) {
		return "credential-secret-reference=" + reference + "\njdbc-url=" + jdbcUrl(port)
				+ "\nusername=nexa_runtime\npassword=" + password + "\n";
	}

	private static void assertTenantScope(Path stateRoot, UUID tenantId, UUID workspaceId, Binding binding)
			throws Exception {
		Path tenantDirectory = stateRoot.resolve(tenantId.toString());
		LocalTenantBusinessDatabaseCredentialsProvider provider =
				new LocalTenantBusinessDatabaseCredentialsProvider(stateRoot);
		var credentials = provider.requireCredentials(binding.credentialSecretReference());
		try (Connection connection = DriverManager.getConnection(credentials.jdbcUrl(), credentials.username(),
				credentials.password());
				PreparedStatement identity = connection.prepareStatement("""
					 SELECT tenant_id, database_identity FROM nexa_platform.tenant_business_database_identity
					 WHERE singleton=TRUE
					 """);
				 PreparedStatement anchor = connection.prepareStatement("""
					 SELECT tenant_id, workspace_id FROM nexa_platform.tenant_workspace_scope_anchor
					 """)) {
			try (ResultSet result = identity.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getObject(1, UUID.class)).isEqualTo(tenantId);
				assertThat(result.getObject(2, UUID.class)).isEqualTo(binding.databaseIdentity());
				assertThat(result.next()).isFalse();
			}
			try (ResultSet result = anchor.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getObject(1, UUID.class)).isEqualTo(tenantId);
				assertThat(result.getObject(2, UUID.class)).isEqualTo(workspaceId);
				assertThat(result.next()).isFalse();
			}
		}
		assertThat(Files.getPosixFilePermissions(tenantDirectory.resolve("credentials.properties")))
				.isEqualTo(PRIVATE_FILE_PERMISSIONS);
		assertPolicySnapshotWriterCredentials(stateRoot, tenantId, workspaceId, binding);
	}

	private static void assertPolicySnapshotWriterCredentials(Path stateRoot, UUID tenantId, UUID workspaceId,
			Binding binding) throws Exception {
		LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider provider =
				new LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(stateRoot);
		var credentials = provider.requireWriterCredentials(new TenantId(tenantId), binding.databaseIdentity());
		try (Connection connection = DriverManager.getConnection(credentials.jdbcUrl(), credentials.username(),
				credentials.password());
			 PreparedStatement privileges = connection.prepareStatement("""
				 SELECT current_user,
				        has_schema_privilege(current_user, 'nexa_platform', 'USAGE'),
				        has_schema_privilege(current_user, 'nexa_platform', 'CREATE'),
				        has_table_privilege(current_user, 'nexa_platform.tenant_business_database_identity', 'SELECT'),
				        has_table_privilege(current_user, 'nexa_platform.tenant_business_database_identity', 'INSERT'),
				        has_table_privilege(current_user, 'nexa_platform.tenant_workspace_scope_anchor', 'SELECT'),
				        has_table_privilege(current_user, 'nexa_platform.tenant_workspace_scope_anchor', 'UPDATE'),
				        has_table_privilege(current_user, 'nexa_platform.purchase_request_expiry_policy_snapshot', 'SELECT'),
				        has_table_privilege(current_user, 'nexa_platform.purchase_request_expiry_policy_snapshot', 'INSERT'),
				        has_table_privilege(current_user, 'nexa_platform.purchase_request_expiry_policy_snapshot', 'UPDATE'),
				        has_table_privilege(current_user, 'nexa_platform.purchase_request_expiry_policy_snapshot', 'DELETE'),
				        has_table_privilege(current_user, 'nexa_platform.purchase_request_expiry_policy_snapshot', 'TRUNCATE'),
				        COALESCE((SELECT has_table_privilege(current_user, relation.oid, 'SELECT')
				                  FROM pg_class relation
				                  JOIN pg_namespace namespace_row ON namespace_row.oid=relation.relnamespace
				                  WHERE namespace_row.nspname='sales' AND relation.relname='purchase_request'), FALSE),
				        EXISTS (SELECT 1 FROM pg_class relation
				                JOIN pg_namespace namespace_row ON namespace_row.oid=relation.relnamespace
				                WHERE namespace_row.nspname='sales' AND relation.relname='purchase_request')
				 """)) {
			assertThat(connection.getMetaData().getUserName()).isEqualTo("nexa_policy_snapshot_writer");
			privileges.setMaxRows(1);
			try (ResultSet result = privileges.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getString(1)).isEqualTo("nexa_policy_snapshot_writer");
				assertThat(result.getBoolean(2)).isTrue();
				assertThat(result.getBoolean(3)).isFalse();
				assertThat(result.getBoolean(4)).isTrue();
				assertThat(result.getBoolean(5)).isFalse();
				assertThat(result.getBoolean(6)).isTrue();
				assertThat(result.getBoolean(7)).isFalse();
				assertThat(result.getBoolean(8)).isTrue();
				assertThat(result.getBoolean(9)).isTrue();
				assertThat(result.getBoolean(10)).isTrue();
				assertThat(result.getBoolean(11)).isFalse();
				assertThat(result.getBoolean(12)).isFalse();
				assertThat(result.getBoolean(13)).isFalse();
				assertThat(result.getBoolean(14)).isTrue();
			}
		}
	}

	private static void assertTenantSchemaWasApplied(int port, String bootstrapPassword, UUID tenantId,
			UUID workspaceId, UUID databaseIdentity) throws Exception {
		String url = jdbcUrl(port);
		try (Connection connection = DriverManager.getConnection(url, "nexa_tenant_bootstrap_admin", bootstrapPassword);
				 PreparedStatement migrations = connection.prepareStatement(
					 "SELECT count(*) FROM public.flyway_schema_history WHERE success=TRUE");
				 ResultSet migrationResult = migrations.executeQuery()) {
			assertThat(migrationResult.next()).isTrue();
			assertThat(migrationResult.getInt(1)).isGreaterThanOrEqualTo(4);
			try (PreparedStatement snapshot = connection.prepareStatement(
					"SELECT count(*) FROM nexa_platform.purchase_request_expiry_policy_snapshot");
				 ResultSet result = snapshot.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).isZero();
			}
			try (PreparedStatement anchor = connection.prepareStatement("""
				 SELECT tenant_id, workspace_id FROM nexa_platform.tenant_workspace_scope_anchor
				 """); ResultSet result = anchor.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getObject(1, UUID.class)).isEqualTo(tenantId);
				assertThat(result.getObject(2, UUID.class)).isEqualTo(workspaceId);
				assertThat(result.next()).isFalse();
			}
			try (PreparedStatement identity = connection.prepareStatement("""
				 SELECT tenant_id, database_identity FROM nexa_platform.tenant_business_database_identity
				 WHERE singleton=TRUE
				 """); ResultSet result = identity.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getObject(1, UUID.class)).isEqualTo(tenantId);
				assertThat(result.getObject(2, UUID.class)).isEqualTo(databaseIdentity);
				assertThat(result.next()).isFalse();
			}
		}
	}

	private static ProcessResult runProvisioner(Path temporaryDirectory, Path centralConfig, Path stateDirectory,
			UUID tenantId, UUID workspaceId, int port, boolean retryFailed) throws Exception {
		Path log = temporaryDirectory.resolve("provision-" + tenantId + "-" + (retryFailed ? "retry" : "run") + ".log");
		writePrivateFile(log, "");
		List<String> command = new ArrayList<>(List.of("bash", PROVISIONER.toString(), tenantId.toString(),
				workspaceId.toString(), Integer.toString(port), "--central-configfile", centralConfig.toString(),
				"--local-state-dir", stateDirectory.toString()));
		if (retryFailed) command.add("--retry-failed");
		Process process = new ProcessBuilder(command).directory(REPOSITORY_ROOT.toFile())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		if (!process.waitFor(240, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("The local Tenant provisioner did not finish within four minutes");
		}
		String output = Files.readString(log, StandardCharsets.UTF_8);
		assertNoTestCredentialsInOutput(output);
		return new ProcessResult(process.exitValue(), output);
	}

	private static void assertProvisionSucceeded(ProcessResult result) {
		assertThat(result.exitCode()).as("provisioner output: %s", redact(result.output())).isZero();
	}

	private static void assertNoTestCredentialsInOutput(String output) {
		assertThat(output.contains(CENTRAL_PASSWORD)).isFalse();
		assertThat(output.contains(BOOTSTRAP_PASSWORD)).isFalse();
		assertThat(output.contains(MIGRATOR_PASSWORD)).isFalse();
		assertThat(output.contains(RUNTIME_PASSWORD)).isFalse();
		assertThat(output.contains(POLICY_SNAPSHOT_WRITER_PASSWORD)).isFalse();
		assertThat(output.contains(WRONG_RUNTIME_PASSWORD)).isFalse();
	}

	private static void assertSeparateLocalPasswords(Path tenantDirectory) throws IOException {
		List<String> passwords = List.of(
				Files.readString(tenantDirectory.resolve("bootstrap-admin-password"), StandardCharsets.UTF_8).strip(),
				Files.readString(tenantDirectory.resolve("migrator-password"), StandardCharsets.UTF_8).strip(),
				Files.readString(tenantDirectory.resolve("runtime-password"), StandardCharsets.UTF_8).strip(),
				Files.readString(tenantDirectory.resolve("policy-snapshot-writer-password"), StandardCharsets.UTF_8).strip());
		assertThat(passwords).doesNotHaveDuplicates().doesNotContain(CENTRAL_PASSWORD);
	}

	private static String redact(String output) {
		return output.replace(CENTRAL_PASSWORD, "[redacted]")
				.replace(BOOTSTRAP_PASSWORD, "[redacted]")
				.replace(MIGRATOR_PASSWORD, "[redacted]")
				.replace(RUNTIME_PASSWORD, "[redacted]")
				.replace(POLICY_SNAPSHOT_WRITER_PASSWORD, "[redacted]")
				.replace(WRONG_RUNTIME_PASSWORD, "[redacted]");
	}

	private static int availableLoopbackPort() throws IOException {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(false);
			socket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
			return socket.getLocalPort();
		}
	}

	private static String jdbcUrl(int port) {
		return "jdbc:postgresql://127.0.0.1:" + port + "/nexa_tenant_business";
	}

	private static String volumeName(UUID tenantId) {
		return "nexa-tenant-business-" + tenantId;
	}

	private static boolean volumeExists(String volumeName) throws Exception {
		Process process = new ProcessBuilder("docker", "volume", "inspect", volumeName)
				.redirectErrorStream(true).start();
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IllegalStateException("Docker volume inspection timed out");
			}
		return process.exitValue() == 0;
	}

	private static void stopTenantDatabase(Path stateRoot, UUID tenantId) throws Exception {
		Path composeEnvironment = stateRoot.resolve(tenantId.toString()).resolve("compose.env");
		if (!Files.isRegularFile(composeEnvironment)) return;
		Process process = new ProcessBuilder("docker", "compose", "--project-name", "nexa-tenant-db-" + tenantId,
				"--env-file", composeEnvironment.toString(), "-f", TENANT_COMPOSE.toString(), "down", "-v",
				"--remove-orphans").redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		if (!process.waitFor(60, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("Disposable Tenant Compose cleanup timed out");
		}
		if (process.exitValue() != 0 || volumeExists(volumeName(tenantId))) {
			throw new IllegalStateException("Disposable Tenant Compose resources were not removed");
		}
	}

	private static Connection centralConnection() throws Exception {
		return DriverManager.getConnection(CENTRAL.getJdbcUrl(), CENTRAL.getUsername(), CENTRAL.getPassword());
	}

	private static String readPrivateValue(Path file, String key) throws Exception {
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			int separator = line.indexOf('=');
			if (separator > 0 && line.substring(0, separator).equals(key)) return line.substring(separator + 1);
		}
		throw new IllegalStateException("Private Tenant state is missing a required value");
	}

	private static void writePrivateFile(Path path, String content) throws Exception {
		Files.writeString(path, content, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(path, PRIVATE_FILE_PERMISSIONS);
	}

	private record Binding(UUID databaseIdentity, String credentialSecretReference, String lifecycleState,
			long version, String verifiedSchemaManifestSha256) { }
	private record ProcessResult(int exitCode, String output) { }
}
