package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.File;
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
import java.sql.Statement;
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
	private static final Path WORKER = REPOSITORY_ROOT.resolve("ops/scripts/tenant-business-database-provisioning-worker.sh");
	private static final Path TENANT_COMPOSE = REPOSITORY_ROOT.resolve("ops/compose/tenant-business.local.compose.yml");
	private static final String CENTRAL_PASSWORD = "tenant-e2e-central-only-test-password";
	private static final String TENANT_DATABASE_PROVISIONER_PASSWORD = "-__7__v_-__7__v_-__7__v_-__7__v_-__7__v_-_8";
	private static final String BOOTSTRAP_PASSWORD = "tenant-e2e-bootstrap-only-test-password";
	private static final String MIGRATOR_PASSWORD = "tenant-e2e-migrator-only-test-password";
	private static final String RUNTIME_PASSWORD = "tenant-e2e-runtime-only-test-password";
	private static final String POLICY_SNAPSHOT_WRITER_PASSWORD = "tenant-e2e-policy-snapshot-writer-only-test-password";
	private static final String WRONG_RUNTIME_PASSWORD = "tenant-e2e-wrong-runtime-only-test-password";
	private static String apiContainerName;
	private static String apiComposeProject;
	private static boolean apiContainerStarted;
	private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	@Container
	private static final PostgreSQLContainer CENTRAL = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa_central_e2e")
			.withUsername("nexa_central_e2e_admin")
			.withPassword(CENTRAL_PASSWORD)
			.withLabel("com.nexa.local-provisioning.disposable-test", "true");

	@BeforeAll
	static void startApiNetworkAnchor() throws Exception {
		apiContainerName = "nexa-modern-api-it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		apiComposeProject = "nexa-tenant-provisioning-it-"
				+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		Process process = new ProcessBuilder("docker", "run", "--detach", "--name", apiContainerName,
				"--label", "com.docker.compose.project=" + apiComposeProject,
				"--label", "com.docker.compose.service=modern-api",
				"postgres:18.4-alpine", "sh", "-ec", "sleep infinity")
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		if (!process.waitFor(90, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("The disposable API network-anchor container did not start in time");
		}
		assertThat(process.exitValue()).as("the disposable API network-anchor container should start").isZero();
		apiContainerStarted = true;
		migrateCentral();
	}

	@AfterAll
	static void stopApiNetworkAnchor() throws Exception {
		if (!apiContainerStarted) return;
		Process process = new ProcessBuilder("docker", "rm", "--force", apiContainerName)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("The disposable API network-anchor container cleanup timed out");
		}
		assertThat(process.exitValue()).as("the disposable API network-anchor container should be removed").isZero();
		apiContainerStarted = false;
	}

	@Test
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	void centralBindingCasCoversReadyFailureRetryAndIdempotentResume(@TempDir Path temporaryDirectory) throws Exception {
		Files.setPosixFilePermissions(temporaryDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		Path resolvedRepositoryRoot = REPOSITORY_ROOT.toRealPath();
		Path resolvedProvisioner = PROVISIONER.toRealPath();
		assertThat(resolvedProvisioner).isEqualTo(
				resolvedRepositoryRoot.resolve("ops/scripts/provision-local-tenant-database.sh"));
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
			assertThat(ready.lifecycleState())
					.as("binding after provisioner exit; script=%s, exit=%s, output tail:\n%s", resolvedProvisioner,
							initial.exitCode(), redactedProvisionerDiagnostic(initial.output()))
					.isEqualTo("READY");
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
			assertThat(redactedProvisionerDiagnostic(failed.output()))
					.as("the seeded wrong runtime password should fail authentication after database provisioning; output tail:\n%s",
							redactedProvisionerDiagnostic(failed.output()))
					.contains("SQLSTATE 28P01");
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
					retryPort, retryTenantId, retryCredentialReference, RUNTIME_PASSWORD));
			makeTenantRuntimeAndMigratorInherit(retryPort);
			ProcessResult retry = runProvisioner(temporaryDirectory, centralConfig, retryStateDirectory,
					retryTenantId, retryWorkspaceId, retryPort, true);
			assertProvisionSucceeded(retry);
			Binding retriedBinding = readBinding(retryTenantId);
			assertThat(retriedBinding.lifecycleState()).isEqualTo("READY");
			assertThat(retriedBinding.version()).isEqualTo(4);
			assertThat(retriedBinding.databaseIdentity()).isEqualTo(retryDatabaseIdentity);
			assertThat(retriedBinding.credentialSecretReference()).isEqualTo(retryCredentialReference);
			Binding firstTenantBinding = readBinding(readyTenantId);
			assertThat(firstTenantBinding.lifecycleState()).isEqualTo("READY");
			assertThat(retriedBinding.databaseIdentity()).isNotEqualTo(firstTenantBinding.databaseIdentity());
			assertThat(retriedBinding.credentialSecretReference()).isNotEqualTo(firstTenantBinding.credentialSecretReference());
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

	@Test
	@Timeout(value = 240, unit = TimeUnit.SECONDS)
	void oneShotWorkerClaimsOnlyRequestedTaskWithDisposableState(@TempDir Path temporaryDirectory) throws Exception {
		Files.setPosixFilePermissions(temporaryDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		Path centralConfig = temporaryDirectory.resolve("worker-central.config");
		writePrivateFile(centralConfig, "container-id=" + CENTRAL.getContainerId() + "\n");
		Path invalidStateDirectory = temporaryDirectory.resolve("worker-tenant-state-nonempty");
		Files.createDirectory(invalidStateDirectory);
		Files.setPosixFilePermissions(invalidStateDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		writePrivateFile(invalidStateDirectory.resolve("preserve-me"), "fixture\n");
		Path dockerShimDirectory = temporaryDirectory.resolve("docker-shim");
		Files.createDirectory(dockerShimDirectory);
		Files.setPosixFilePermissions(dockerShimDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		Path dockerMarker = temporaryDirectory.resolve("docker-was-called");
		Path dockerShim = dockerShimDirectory.resolve("docker");
		Files.writeString(dockerShim, "#!/bin/sh\ncase \"${1:-}:${2:-}\" in\n"
				+ "  context:show) printf '%s\\n' default ;;\n"
				+ "  context:inspect) printf '%s\\n' unix:///var/run/docker.sock ;;\n"
				+ "  info:) exit 0 ;;\n"
				+ "  inspect:*) printf '%s\\n' \"$ONE_SHOT_INSPECT_RESULT\" ;;\n"
				+ "  exec:*) printf '%s\\n' invoked > \"$ONE_SHOT_DOCKER_MARKER\"; exit 97 ;;\n"
				+ "  *) exit 98 ;;\nesac\n",
				StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(dockerShim, Set.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		ProcessResult rejected = runWorkerOnce(temporaryDirectory, centralConfig, invalidStateDirectory,
				UUID.randomUUID(), dockerShimDirectory, dockerMarker);
		assertThat(rejected.exitCode()).isNotZero();
		assertThat(redact(rejected.output())).contains("The one-shot local state directory must be empty.");
		assertThat(Files.exists(dockerMarker)).isFalse();
		assertThat(Files.readString(invalidStateDirectory.resolve("preserve-me"), StandardCharsets.UTF_8))
				.isEqualTo("fixture\n");

		Path composeRejectedStateDirectory = temporaryDirectory.resolve("worker-tenant-state-compose-rejected");
		Files.createDirectory(composeRejectedStateDirectory);
		Files.setPosixFilePermissions(composeRejectedStateDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		String composeInspection = CENTRAL.getContainerId()
				+ "|running|postgres:18.4-alpine|nexa-modern|modern-postgres|true";
		ProcessResult composeRejected = runWorkerOnce(temporaryDirectory, centralConfig,
				composeRejectedStateDirectory, UUID.randomUUID(), dockerShimDirectory, dockerMarker, composeInspection);
		assertThat(composeRejected.exitCode()).isNotZero();
		assertThat(redact(composeRejected.output())).contains("One-shot Tenant provisioning refuses Compose-managed PostgreSQL containers.");
		assertThat(Files.exists(dockerMarker)).isFalse();

		Path unmarkedStateDirectory = temporaryDirectory.resolve("worker-tenant-state-unmarked");
		Files.createDirectory(unmarkedStateDirectory);
		Files.setPosixFilePermissions(unmarkedStateDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		String unmarkedInspection = CENTRAL.getContainerId() + "|running|postgres:18.4-alpine|||false";
		ProcessResult unmarkedRejected = runWorkerOnce(temporaryDirectory, centralConfig,
				unmarkedStateDirectory, UUID.randomUUID(), dockerShimDirectory, dockerMarker, unmarkedInspection);
		assertThat(unmarkedRejected.exitCode()).isNotZero();
		assertThat(redact(unmarkedRejected.output()).toLowerCase())
				.contains("explicitly disposable");
		assertThat(Files.exists(dockerMarker)).isFalse();

		Path localStateDirectory = temporaryDirectory.resolve("worker-tenant-state");
		Files.createDirectory(localStateDirectory);
		Files.setPosixFilePermissions(localStateDirectory, PRIVATE_DIRECTORY_PERMISSIONS);

		UUID earlierTenantId = UUID.randomUUID();
		UUID earlierWorkspaceId = UUID.randomUUID();
		UUID earlierTaskId = UUID.randomUUID();
		seedTenantAndWorkspace(earlierTenantId, earlierWorkspaceId);
		insertProvisioningTask(earlierTaskId, earlierTenantId, earlierWorkspaceId,
				OffsetDateTime.now().minusHours(1).withNano(0));

		UUID selectedTenantId = UUID.randomUUID();
		UUID selectedWorkspaceId = UUID.randomUUID();
		UUID selectedTaskId = UUID.randomUUID();
		UUID selectedAnchorTaskId = UUID.randomUUID();
		seedTenantAndWorkspace(selectedTenantId, selectedWorkspaceId);
		OffsetDateTime selectedCreatedAt = OffsetDateTime.now().withNano(0);
		insertProvisioningTask(selectedTaskId, selectedTenantId, selectedWorkspaceId, selectedCreatedAt);
		insertWorkspaceAnchorTask(selectedAnchorTaskId, selectedTenantId, selectedWorkspaceId, selectedCreatedAt);
		String selectedVolume = volumeName(selectedTenantId);
		assertThat(volumeExists(selectedVolume)).isFalse();
		try {
			ProcessResult worker = runWorkerOnce(temporaryDirectory, centralConfig, localStateDirectory, selectedTaskId);
			assertThat(worker.exitCode()).as("one-shot worker output: %s", redact(worker.output())).isZero();
			assertNoTestCredentialsInOutput(worker.output());
			assertThat(redact(worker.output())).contains("Provisioning task " + selectedTaskId + " reached READY");

			ProvisioningTask selected = readProvisioningTask(selectedTaskId);
			assertThat(selected.status()).isEqualTo("READY");
			assertThat(selected.attemptCount()).isEqualTo(1);
			assertThat(selected.databasePort()).isNotNull();
			assertThat(selected.claimToken()).isNull();
			assertThat(readBinding(selectedTenantId).lifecycleState()).isEqualTo("READY");

			ProvisioningTask earlier = readProvisioningTask(earlierTaskId);
			assertThat(earlier.status()).isEqualTo("PENDING");
			assertThat(earlier.attemptCount()).isZero();
			assertThat(earlier.databasePort()).isNull();
			assertThat(earlier.claimToken()).isNull();
			WorkspaceAnchorTask anchor = readWorkspaceAnchorTask(selectedAnchorTaskId);
			assertThat(anchor.status()).isEqualTo("PENDING");
			assertThat(anchor.attemptCount()).isZero();
			assertThat(anchor.claimToken()).isNull();
			assertThat(Files.getPosixFilePermissions(centralConfig)).isEqualTo(PRIVATE_FILE_PERMISSIONS);
			assertThat(readPrivateValue(centralConfig, "container-id")).isEqualTo(CENTRAL.getContainerId());
			assertThat(Files.exists(localStateDirectory.resolve("worker.pid"))).isFalse();
			assertThat(Files.exists(localStateDirectory.resolve("worker.ready"))).isFalse();
			assertThat(volumeExists(selectedVolume)).isTrue();
		} finally {
			stopTenantDatabase(localStateDirectory, selectedTenantId);
		}
	}

	private static void migrateCentral() {
		try (Connection connection = centralConnection(); var statement = connection.createStatement()) {
			statement.execute("CREATE ROLE nexa_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
					+ RUNTIME_PASSWORD + "'");
			statement.execute("CREATE ROLE nexa_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
					+ MIGRATOR_PASSWORD + "'");
			statement.execute("CREATE ROLE nexa_tenant_database_provisioner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
					+ TENANT_DATABASE_PROVISIONER_PASSWORD + "'");
		} catch (Exception exception) {
			throw new IllegalStateException("Could not create the disposable Tenant database provisioner role", exception);
		}
		Flyway.configure().dataSource(CENTRAL.getJdbcUrl(), CENTRAL.getUsername(), CENTRAL.getPassword())
				.locations("classpath:db/migration").target("168").load().migrate();
		try (Connection connection = centralConnection(); var statement = connection.createStatement()) {
			statement.execute(Files.readString(REPOSITORY_ROOT.resolve("ops/database/prepare-v169-tenant-provisioner.sql")));
		} catch (Exception exception) {
			throw new IllegalStateException("Could not provision exact V169 scope-helper grants", exception);
		}
		Flyway.configure().dataSource(CENTRAL.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("169").load().migrate();
		try (Connection connection = centralConnection();			 PreparedStatement statement = connection.prepareStatement(
					"SELECT max(version::INTEGER) FROM flyway_schema_history WHERE success = TRUE");
				 ResultSet result = statement.executeQuery()) {
			if (!result.next() || result.getInt(1) != 169) {
				throw new IllegalStateException("The disposable central database did not reach V169");
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Could not verify disposable central migration version", exception);
		}
			try (Connection connection = centralConnection();
				 PreparedStatement statement = connection.prepareStatement("""
						 SELECT pg_get_userbyid(p.proowner), r.rolsuper, r.rolbypassrls
						 FROM pg_proc p JOIN pg_roles r ON r.rolname='nexa_migrator'
						 WHERE p.oid='tenant_management.provisioner_scope_exists(uuid, uuid)'::regprocedure
						 """);
				 ResultSet result = statement.executeQuery()) {
				if (!result.next() || !"nexa_migrator".equals(result.getString(1))
						|| result.getBoolean(2) || result.getBoolean(3)) {
					throw new IllegalStateException("The central scope helper is not owned by a restricted migration role");
				}
			} catch (Exception exception) {
				throw new IllegalStateException("Could not verify central scope helper owner", exception);
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

	private static void insertProvisioningTask(UUID taskId, UUID tenantId, UUID workspaceId, OffsetDateTime createdAt)
			throws Exception {
		try (Connection connection = centralConnection();
				 PreparedStatement statement = connection.prepareStatement("""
					 INSERT INTO tenant_management.tenant_business_database_provisioning_task
					     (task_id,tenant_id,workspace_id,created_at,updated_at)
					 VALUES (?,?,?,?,?)
					 """)) {
			statement.setObject(1, taskId);
			statement.setObject(2, tenantId);
			statement.setObject(3, workspaceId);
			statement.setObject(4, createdAt);
			statement.setObject(5, createdAt);
			statement.executeUpdate();
		}
	}

	private static void insertWorkspaceAnchorTask(UUID taskId, UUID tenantId, UUID workspaceId,
			OffsetDateTime createdAt) throws Exception {
		try (Connection connection = centralConnection();
				 PreparedStatement statement = connection.prepareStatement("""
					 INSERT INTO tenant_management.tenant_business_database_workspace_anchor_task
					     (task_id,tenant_id,workspace_id,created_at,updated_at)
					 VALUES (?,?,?,?,?)
					 """)) {
			statement.setObject(1, taskId);
			statement.setObject(2, tenantId);
			statement.setObject(3, workspaceId);
			statement.setObject(4, createdAt);
			statement.setObject(5, createdAt);
			statement.executeUpdate();
		}
	}

	private static ProvisioningTask readProvisioningTask(UUID taskId) throws Exception {
		try (Connection connection = centralConnection();
				 PreparedStatement statement = connection.prepareStatement("""
					 SELECT status,attempt_count,database_port,claim_token
					 FROM tenant_management.tenant_business_database_provisioning_task WHERE task_id=?
					 """)) {
			statement.setObject(1, taskId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("The provisioning task was not persisted");
				int portValue = result.getInt("database_port");
				Integer port = result.wasNull() ? null : portValue;
				ProvisioningTask task = new ProvisioningTask(result.getString("status"),
						result.getInt("attempt_count"), port, result.getObject("claim_token", UUID.class));
				if (result.next()) throw new IllegalStateException("The provisioning task id is not unique");
				return task;
			}
		}
	}

	private static WorkspaceAnchorTask readWorkspaceAnchorTask(UUID taskId) throws Exception {
		try (Connection connection = centralConnection();
				 PreparedStatement statement = connection.prepareStatement("""
					 SELECT status,attempt_count,claim_token
					 FROM tenant_management.tenant_business_database_workspace_anchor_task WHERE task_id=?
					 """)) {
			statement.setObject(1, taskId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalStateException("The Workspace anchor task was not persisted");
				WorkspaceAnchorTask task = new WorkspaceAnchorTask(result.getString("status"),
						result.getInt("attempt_count"), result.getObject("claim_token", UUID.class));
				if (result.next()) throw new IllegalStateException("The Workspace anchor task id is not unique");
				return task;
			}
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
				port, tenantId, credentialReference, WRONG_RUNTIME_PASSWORD));
		writePrivateFile(tenantDirectory.resolve("policy-snapshot-writer.properties"),
				"jdbc-url=" + jdbcUrl(port) + "\napi-jdbc-url=jdbc:postgresql://tenant-db-" + tenantId
						+ ":5432/nexa_tenant_business\nusername=nexa_policy_snapshot_writer\npassword="
						+ POLICY_SNAPSHOT_WRITER_PASSWORD + "\n");
		writePrivateFile(tenantDirectory.resolve("state.properties"), "tenant-id=" + tenantId
				+ "\nworkspace-id=" + workspaceId + "\ndatabase-identity=" + databaseIdentity
				+ "\ncredential-secret-reference=" + credentialReference + "\ndatabase-port=" + port + "\n");
		writePrivateFile(tenantDirectory.resolve("compose.env"), "NEXA_TENANT_DATABASE_PORT=" + port
				+ "\nNEXA_TENANT_DATABASE_ID=" + tenantId
				+ "\nNEXA_TENANT_DATABASE_SECRET_DIR=" + tenantDirectory.toRealPath() + "\n");
		return tenantDirectory;
	}

	private static String runtimeCredentials(int port, UUID tenantId, String reference, String password) {
		return "credential-secret-reference=" + reference + "\njdbc-url=" + jdbcUrl(port)
				+ "\napi-jdbc-url=jdbc:postgresql://tenant-db-" + tenantId + ":5432/nexa_tenant_business"
				+ "\nusername=nexa_runtime\npassword=" + password + "\n";
	}

	private static void assertTenantScope(Path stateRoot, UUID tenantId, UUID workspaceId, Binding binding)
			throws Exception {
		Path tenantDirectory = stateRoot.resolve(tenantId.toString());
		LocalTenantBusinessDatabaseCredentialsProvider provider =
				new LocalTenantBusinessDatabaseCredentialsProvider(stateRoot);
		var credentials = provider.requireHostCredentials(binding.credentialSecretReference());
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
			try (PreparedStatement roleInheritance = connection.prepareStatement("""
					SELECT COUNT(*)=2 AND bool_and(NOT rolinherit)
					FROM pg_roles WHERE rolname IN ('nexa_runtime', 'nexa_migrator')
					""");
				 ResultSet result = roleInheritance.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getBoolean(1)).as("Tenant runtime and migrator roles must be NOINHERIT").isTrue();
			}
		}
		assertThat(Files.getPosixFilePermissions(tenantDirectory.resolve("credentials.properties")))
				.isEqualTo(PRIVATE_FILE_PERMISSIONS);
		assertPolicySnapshotWriterCredentials(stateRoot, tenantId, workspaceId, binding);
	}

	private static void makeTenantRuntimeAndMigratorInherit(int port) throws Exception {
		try (Connection connection = DriverManager.getConnection(jdbcUrl(port), "nexa_tenant_bootstrap_admin",
				BOOTSTRAP_PASSWORD);
			 Statement statement = connection.createStatement()) {
			statement.execute("ALTER ROLE nexa_runtime INHERIT");
			statement.execute("ALTER ROLE nexa_migrator INHERIT");
		}
	}

	private static void assertPolicySnapshotWriterCredentials(Path stateRoot, UUID tenantId, UUID workspaceId,
			Binding binding) throws Exception {
		LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider provider =
				new LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(stateRoot);
		var credentials = provider.requireHostWriterCredentials(new TenantId(tenantId), binding.databaseIdentity());
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
		ProcessBuilder processBuilder = new ProcessBuilder(command).directory(REPOSITORY_ROOT.toFile())
				.redirectErrorStream(true).redirectOutput(log.toFile());
		processBuilder.environment().put("NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD", TENANT_DATABASE_PROVISIONER_PASSWORD);
		processBuilder.environment().put("NEXA_LOCAL_API_CONTAINER_NAME", apiContainerName);
		processBuilder.environment().put("NEXA_LOCAL_API_COMPOSE_PROJECT", apiComposeProject);
		Process process = processBuilder.start();
		if (!process.waitFor(240, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("The local Tenant provisioner did not finish within four minutes");
		}
		String output = Files.readString(log, StandardCharsets.UTF_8);
		assertNoTestCredentialsInOutput(output);
		return new ProcessResult(process.exitValue(), output);
	}

	private static ProcessResult runWorkerOnce(Path temporaryDirectory, Path centralConfig, Path localStateDirectory,
			UUID taskId) throws Exception {
		return runWorkerOnce(temporaryDirectory, centralConfig, localStateDirectory, taskId, null, null);
	}

	private static ProcessResult runWorkerOnce(Path temporaryDirectory, Path centralConfig, Path localStateDirectory,
			UUID taskId, Path dockerShimDirectory, Path dockerMarker) throws Exception {
		return runWorkerOnce(temporaryDirectory, centralConfig, localStateDirectory, taskId,
				dockerShimDirectory, dockerMarker, null);
	}

	private static ProcessResult runWorkerOnce(Path temporaryDirectory, Path centralConfig, Path localStateDirectory,
			UUID taskId, Path dockerShimDirectory, Path dockerMarker, String dockerInspection) throws Exception {
		Path log = temporaryDirectory.resolve("worker-once-" + taskId + ".log");
		writePrivateFile(log, "");
		List<String> command = List.of("bash", WORKER.toString(), "--once", "--task-id", taskId.toString(),
				"--central-configfile", centralConfig.toString(), "--local-state-dir", localStateDirectory.toString());
		ProcessBuilder processBuilder = new ProcessBuilder(command).directory(REPOSITORY_ROOT.toFile())
				.redirectErrorStream(true).redirectOutput(log.toFile());
		processBuilder.environment().put("NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD", TENANT_DATABASE_PROVISIONER_PASSWORD);
		processBuilder.environment().put("NEXA_LOCAL_API_CONTAINER_NAME", apiContainerName);
		processBuilder.environment().put("NEXA_LOCAL_API_COMPOSE_PROJECT", apiComposeProject);
		if (dockerShimDirectory != null && dockerMarker != null) {
			String path = processBuilder.environment().getOrDefault("PATH", "");
			processBuilder.environment().put("PATH", dockerShimDirectory + File.pathSeparator + path);
			processBuilder.environment().put("ONE_SHOT_DOCKER_MARKER", dockerMarker.toString());
			processBuilder.environment().put("ONE_SHOT_INSPECT_RESULT", dockerInspection == null ? "" : dockerInspection);
		}
		Process process = processBuilder.start();
		if (!process.waitFor(180, TimeUnit.SECONDS)) {
			List<ProcessHandle> descendants = process.toHandle().descendants().toList();
			process.destroy();
			process.waitFor(15, TimeUnit.SECONDS);
			descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
			for (int attempt = 0; attempt < 50 && descendants.stream().anyMatch(ProcessHandle::isAlive); attempt++) {
				Thread.sleep(100);
			}
			descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
			if (process.isAlive()) process.destroyForcibly();
			process.waitFor(10, TimeUnit.SECONDS);
			throw new IllegalStateException("The one-shot Tenant provisioning worker timed out and its process tree was stopped");
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
		assertThat(output.contains(TENANT_DATABASE_PROVISIONER_PASSWORD)).isFalse();
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
				.replace(TENANT_DATABASE_PROVISIONER_PASSWORD, "[redacted]")
				.replace(BOOTSTRAP_PASSWORD, "[redacted]")
				.replace(MIGRATOR_PASSWORD, "[redacted]")
				.replace(RUNTIME_PASSWORD, "[redacted]")
				.replace(POLICY_SNAPSHOT_WRITER_PASSWORD, "[redacted]")
				.replace(WRONG_RUNTIME_PASSWORD, "[redacted]");
	}

	private static String redactedProvisionerDiagnostic(String output) {
		String sanitized = redact(output)
				.replaceAll("(?i)(password|passwd|secret|token|authorization)(\\s*[:=]\\s*)[^\\s,;]+",
						"$1$2[redacted]")
				.replaceAll("(?i)(jdbc:postgresql://[^/@\\s:]+:)[^@/\\s]+@", "$1[redacted]@");
		if (sanitized.length() > 4_000) {
			int start = sanitized.length() - 4_000;
			int nextLine = sanitized.indexOf('\n', start);
			if (nextLine >= 0) start = nextLine + 1;
			sanitized = sanitized.substring(start);
		}
		return sanitized;
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
		disconnectTestApiAnchorFromTenantNetwork(stateRoot, tenantId);
		String projectName = "nexa-tenant-db-" + tenantId;
		ProcessResult cleanup = runDockerCommand(stateRoot, List.of("compose", "--project-name", projectName,
				"--env-file", composeEnvironment.toString(), "-f", TENANT_COMPOSE.toString(), "down", "-v",
				"--remove-orphans"), 60);
		boolean volumeRemains = volumeExists(volumeName(tenantId));
		boolean networkRemains = dockerNetworkExists(stateRoot, tenantNetworkName(tenantId));
		if (cleanup.exitCode() != 0 || volumeRemains || networkRemains) {
			String networkDiagnostic = networkRemains
					? inspectTenantNetworkEndpoints(stateRoot, tenantNetworkName(tenantId)) : "absent";
			throw new IllegalStateException("Disposable Tenant Compose cleanup was incomplete for " + tenantId
					+ " (compose exit=" + cleanup.exitCode() + ", volume remains=" + volumeRemains
					+ ", network remains=" + networkRemains + ", remaining endpoints="
					+ redactedProvisionerDiagnostic(networkDiagnostic) + ", compose output="
					+ redactedProvisionerDiagnostic(cleanup.output()) + ")");
	}
	}

	private static void disconnectTestApiAnchorFromTenantNetwork(Path diagnosticDirectory, UUID tenantId)
			throws Exception {
		String networkName = tenantNetworkName(tenantId);
		if (!dockerNetworkExists(diagnosticDirectory, networkName)) return;

		String expectedProject = "nexa-tenant-db-" + tenantId;
		ProcessResult networkInspection = runDockerCommand(diagnosticDirectory, List.of("network", "inspect",
				"--format", "{{.Id}}|{{.Driver}}|{{index .Labels \"com.docker.compose.project\"}}|"
						+ "{{index .Labels \"com.docker.compose.network\"}}", networkName), 30);
		if (networkInspection.exitCode() != 0) {
			throw new IllegalStateException("Could not verify the disposable Tenant test network " + networkName
					+ " before cleanup: " + redactedProvisionerDiagnostic(networkInspection.output()));
		}
		String[] networkIdentity = networkInspection.output().strip().split("\\|", -1);
		if (networkIdentity.length != 4 || !networkIdentity[1].equals("bridge")
				|| !networkIdentity[2].equals(expectedProject) || !networkIdentity[3].equals("tenant-database")) {
			throw new IllegalStateException("Refusing to disconnect the test API anchor from an unverified Tenant network "
					+ networkName + " (expected project=" + expectedProject + ", network=tenant-database, bridge; actual="
					+ redactedProvisionerDiagnostic(networkInspection.output()) + ")");
		}

		if (apiContainerName == null || apiComposeProject == null) return;
		ProcessResult apiInspection = runDockerCommand(diagnosticDirectory, List.of("inspect", "--format",
				"{{.Id}}|{{index .Config.Labels \"com.docker.compose.service\"}}|"
						+ "{{index .Config.Labels \"com.docker.compose.project\"}}|"
						+ "{{with index .NetworkSettings.Networks \"" + networkName + "\"}}{{.NetworkID}}{{end}}",
				apiContainerName), 30);
		if (apiInspection.exitCode() != 0) {
			throw new IllegalStateException("Could not verify the disposable API anchor before Tenant test cleanup: "
					+ redactedProvisionerDiagnostic(apiInspection.output()));
		}
		String[] apiIdentity = apiInspection.output().strip().split("\\|", -1);
		if (apiIdentity.length != 4 || !apiIdentity[1].equals("modern-api")
				|| !apiIdentity[2].equals(apiComposeProject)) {
			throw new IllegalStateException("Refusing to disconnect a container that is not this test's API anchor "
					+ apiContainerName + " (expected Compose project=" + apiComposeProject + ", service=modern-api; actual="
					+ redactedProvisionerDiagnostic(apiInspection.output()) + ")");
		}
		String networkId = networkIdentity[0];
		String attachedNetworkId = apiIdentity[3];
		if (attachedNetworkId.isEmpty()) return;
		if (!attachedNetworkId.equals(networkId)) {
			throw new IllegalStateException("The disposable API anchor reports an unexpected network identity for "
					+ networkName + "; refusing to alter Docker networking.");
		}

		ProcessResult disconnect = runDockerCommand(diagnosticDirectory,
				List.of("network", "disconnect", "--force", networkName, apiContainerName), 30);
		if (disconnect.exitCode() != 0) {
			throw new IllegalStateException("Could not disconnect this test's API anchor from " + networkName + ": "
					+ redactedProvisionerDiagnostic(disconnect.output()));
		}
	}

	private static boolean dockerNetworkExists(Path diagnosticDirectory, String networkName) throws Exception {
		ProcessResult networks = runDockerCommand(diagnosticDirectory, List.of("network", "ls", "--format", "{{.Name}}"),
				30);
		if (networks.exitCode() != 0) {
			throw new IllegalStateException("Could not inspect Docker networks during disposable Tenant cleanup: "
					+ redactedProvisionerDiagnostic(networks.output()));
		}
		return networks.output().lines().map(String::strip).anyMatch(networkName::equals);
	}

	private static String inspectTenantNetworkEndpoints(Path diagnosticDirectory, String networkName) throws Exception {
		ProcessResult inspection = runDockerCommand(diagnosticDirectory, List.of("network", "inspect", "--format",
				"{{range $id, $container := .Containers}}{{$container.Name}} {{end}}", networkName), 30);
		return inspection.exitCode() == 0 ? inspection.output().strip()
				: "network inspection failed (exit=" + inspection.exitCode() + ")";
	}

	private static String tenantNetworkName(UUID tenantId) {
		return "nexa-tenant-business-" + tenantId;
	}

	private static ProcessResult runDockerCommand(Path diagnosticDirectory, List<String> arguments, long timeoutSeconds)
			throws Exception {
		Path outputFile = diagnosticDirectory.resolve(".docker-cleanup-" + UUID.randomUUID() + ".log");
		writePrivateFile(outputFile, "");
		List<String> command = new ArrayList<>();
		command.add("docker");
		command.addAll(arguments);
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile())
					.start();
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroy();
				if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
				throw new IllegalStateException("Docker command timed out during disposable Tenant cleanup");
			}
			return new ProcessResult(process.exitValue(), Files.readString(outputFile, StandardCharsets.UTF_8));
		} finally {
			Files.deleteIfExists(outputFile);
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
	private record ProvisioningTask(String status, int attemptCount, Integer databasePort, UUID claimToken) { }
	private record WorkspaceAnchorTask(String status, int attemptCount, UUID claimToken) { }
	private record ProcessResult(int exitCode, String output) { }
}
