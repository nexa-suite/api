package com.nexa.api.support;

import com.nexa.api.tenantaccessgovernance.iam.infrastructure.notification.JdbcSecurityNotificationOutboxAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.annotation.DirtiesContext;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Connects real activation, one-shot provisioning, tenant routing and persisted business data. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrganizationActivationProvisioningRoutingE2EIT extends PostgresIntegrationSupport {
	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path WORKER = REPOSITORY_ROOT.resolve("ops/scripts/tenant-business-database-provisioning-worker.sh");
	private static final Path TENANT_COMPOSE = REPOSITORY_ROOT.resolve("ops/compose/tenant-business.local.compose.yml");
	private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
	private static String apiContainerName;
	private static String apiComposeProject;
	private static boolean apiContainerStarted;
	@Autowired
	private JdbcSecurityNotificationOutboxAdapter notificationOutbox;

	@BeforeAll
	static void startDisposableApiNetworkAnchor() throws Exception {
		apiContainerName = "nexa-modern-api-it-" + compactUuid();
		apiComposeProject = "nexa-tenant-activation-it-" + compactUuid();
		Process process = new ProcessBuilder("docker", "run", "--detach", "--name", apiContainerName,
				"--label", "com.docker.compose.project=" + apiComposeProject,
				"--label", "com.docker.compose.service=modern-api", "postgres:18.4-alpine", "sh", "-ec",
				"sleep infinity").redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		if (!process.waitFor(90, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("The disposable API network anchor did not start in time");
		}
		assertThat(process.exitValue()).as("the disposable API network anchor should start").isZero();
		apiContainerStarted = true;
	}

	@AfterAll
	static void stopDisposableApiNetworkAnchor() throws Exception {
		if (!apiContainerStarted) return;
		Process process = new ProcessBuilder("docker", "rm", "--force", apiContainerName)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("The disposable API network anchor cleanup timed out");
		}
		assertThat(process.exitValue()).as("the disposable API network anchor should be removed").isZero();
		apiContainerStarted = false;
	}

	@Test
	@Timeout(value = 600, unit = TimeUnit.SECONDS)
	void activationProvisioningAndApiWriteUseTheNewTenantsPhysicalDatabase(@TempDir Path temporaryDirectory)
			throws Exception {
		Files.setPosixFilePermissions(temporaryDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		Path centralConfig = temporaryDirectory.resolve("central.config");
		writePrivateFile(centralConfig, "container-id=" + POSTGRES.getContainerId() + "\n");
		Path localStateDirectory = temporaryDirectory.resolve("tenant-state");
		Files.createDirectory(localStateDirectory);
		Files.setPosixFilePermissions(localStateDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
		try (Stream<Path> entries = Files.list(localStateDirectory)) {
			assertThat(entries).isEmpty();
		}

		String workspaceSlug = "it-provision-" + compactUuid().substring(0, 12);
		MvcResult registration = mockMvc.perform(post("/api/v1/tenant-management/organization-registrations")
					.header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
					.content(OrganizationRegistrationPayload.forFounder(OWNER_EMAIL, workspaceSlug)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_ACTIVATION"))
			.andReturn();
		String registrationId = responseJson(registration).get("registrationId").asText();
		MvcResult activation = mockMvc.perform(post("/api/v1/internal/organization-registrations/"
					+ registrationId + "/activation")
				.header("X-Nexa-System-Operator",
						"integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();

		UUID tenantId = UUID.fromString(responseJson(activation).get("tenantId").asText());
		UUID workspaceId = UUID.fromString(responseJson(activation).get("workspaceId").asText());
		UUID provisioningTaskId = jdbc.queryForObject("""
				SELECT task_id FROM tenant_management.tenant_business_database_provisioning_task
				 WHERE tenant_id=? AND workspace_id=? AND status='PENDING'
				 ORDER BY task_id
				""", UUID.class, tenantId, workspaceId);
		UUID workspaceAnchorTaskId = jdbc.queryForObject("""
				SELECT task_id FROM tenant_management.tenant_business_database_workspace_anchor_task
				 WHERE tenant_id=? AND workspace_id=? AND status='PENDING'
				 ORDER BY task_id
				""", UUID.class, tenantId, workspaceId);
		String volumeName = "nexa-tenant-business-" + tenantId;
		Path tenantStateDirectory = localStateDirectory.resolve(tenantId.toString());
		Path parkedStateRoot = temporaryDirectory.resolve("parked-tenant-state");
		Path parkedTenantStateDirectory = parkedStateRoot.resolve(tenantId.toString());
		try {
			UUID competingAnchorClaim = UUID.randomUUID();
			assertThat(jdbc.update("""
					UPDATE tenant_management.tenant_business_database_workspace_anchor_task
					   SET status='LEASED', attempt_count=attempt_count+1, claim_token=?,
				       lease_until=current_timestamp + interval '30 minutes', next_attempt_at=current_timestamp,
				       updated_at=current_timestamp, version=version+1
				 WHERE task_id=? AND status='PENDING'
					""", competingAnchorClaim, workspaceAnchorTaskId)).isEqualTo(1);
			ProcessResult firstWorker = runWorkerOnce(temporaryDirectory, centralConfig, localStateDirectory,
					provisioningTaskId, true);
			assertThat(firstWorker.exitCode()).as("one-shot provisioning output: %s", safeOutput(firstWorker.output())).isEqualTo(3);
			assertThat(safeOutput(firstWorker.output())).contains("Provisioning task " + provisioningTaskId + " reached READY");
			assertThat(safeOutput(firstWorker.output())).contains("is not currently claimable");
			assertThat(jdbc.queryForObject("select status from tenant_management.tenant_business_database_provisioning_task where task_id=?",
					String.class, provisioningTaskId)).isEqualTo("READY");
			assertThat(jdbc.queryForObject("select status from tenant_management.tenant_business_database_workspace_anchor_task where task_id=?",
					String.class, workspaceAnchorTaskId)).isEqualTo("LEASED");
			Integer provisioningAttempts = jdbc.queryForObject("select attempt_count from tenant_management.tenant_business_database_provisioning_task where task_id=?",
					Integer.class, provisioningTaskId);
			Integer provisioningPort = jdbc.queryForObject("select database_port from tenant_management.tenant_business_database_provisioning_task where task_id=?",
					Integer.class, provisioningTaskId);
			assertThat(jdbc.update("""
					UPDATE tenant_management.tenant_business_database_workspace_anchor_task
					   SET lease_until=current_timestamp - interval '1 second', updated_at=current_timestamp
					 WHERE task_id=? AND status='LEASED' AND claim_token=?
					""", workspaceAnchorTaskId, competingAnchorClaim)).isEqualTo(1);

			ProcessResult worker = runWorkerOnce(temporaryDirectory, centralConfig, localStateDirectory,
					provisioningTaskId, true);
			assertThat(worker.exitCode()).as("one-shot provisioning retry output: %s", safeOutput(worker.output())).isZero();
			assertThat(safeOutput(worker.output())).contains("Provisioning task " + provisioningTaskId
					+ " is already READY; skipping Tenant database reprovisioning");
			assertThat(safeOutput(worker.output())).contains("Workspace anchor task " + workspaceAnchorTaskId + " reached READY");
			assertThat(jdbc.queryForObject("select status from tenant_management.tenant_business_database_provisioning_task where task_id=?",
					String.class, provisioningTaskId)).isEqualTo("READY");
			assertThat(jdbc.queryForObject("select attempt_count from tenant_management.tenant_business_database_provisioning_task where task_id=?",
					Integer.class, provisioningTaskId)).isEqualTo(provisioningAttempts);
			assertThat(jdbc.queryForObject("select database_port from tenant_management.tenant_business_database_provisioning_task where task_id=?",
					Integer.class, provisioningTaskId)).isEqualTo(provisioningPort);
			assertThat(jdbc.queryForObject("select status from tenant_management.tenant_business_database_workspace_anchor_task where task_id=?",
					String.class, workspaceAnchorTaskId)).isEqualTo("READY");
			assertThat(jdbc.queryForObject("select lifecycle_state from tenant_management.tenant_business_database_binding where tenant_id=?",
					String.class, tenantId)).isEqualTo("READY");
			Integer readyAnchorAttempts = jdbc.queryForObject(
					"select attempt_count from tenant_management.tenant_business_database_workspace_anchor_task where task_id=?",
					Integer.class, workspaceAnchorTaskId);
			Long readyProvisioningVersion = jdbc.queryForObject(
					"select version from tenant_management.tenant_business_database_provisioning_task where task_id=?",
					Long.class, provisioningTaskId);
			Long readyAnchorVersion = jdbc.queryForObject(
					"select version from tenant_management.tenant_business_database_workspace_anchor_task where task_id=?",
					Long.class, workspaceAnchorTaskId);
			Long readyBindingVersion = jdbc.queryForObject(
					"select version from tenant_management.tenant_business_database_binding where tenant_id=?",
					Long.class, tenantId);
			Files.createDirectory(parkedStateRoot);
			Files.setPosixFilePermissions(parkedStateRoot, PRIVATE_DIRECTORY_PERMISSIONS);
			Files.move(tenantStateDirectory, parkedTenantStateDirectory);
			try {
				ProcessResult missingLocalState = runWorkerOnce(temporaryDirectory, centralConfig, localStateDirectory,
						provisioningTaskId, true);
				assertThat(missingLocalState.exitCode())
						.as("a centrally READY task without private Tenant state must fail: %s",
								safeOutput(missingLocalState.output()))
						.isNotZero();
				assertThat(safeOutput(missingLocalState.output()))
						.contains("private Tenant state or volume failed read-only verification");
				assertThat(jdbc.queryForObject("select status from tenant_management.tenant_business_database_provisioning_task where task_id=?",
						String.class, provisioningTaskId)).isEqualTo("READY");
				assertThat(jdbc.queryForObject("select attempt_count from tenant_management.tenant_business_database_provisioning_task where task_id=?",
						Integer.class, provisioningTaskId)).isEqualTo(provisioningAttempts);
				assertThat(jdbc.queryForObject("select database_port from tenant_management.tenant_business_database_provisioning_task where task_id=?",
						Integer.class, provisioningTaskId)).isEqualTo(provisioningPort);
				assertThat(jdbc.queryForObject("select version from tenant_management.tenant_business_database_provisioning_task where task_id=?",
						Long.class, provisioningTaskId)).isEqualTo(readyProvisioningVersion);
				assertThat(jdbc.queryForObject("select status from tenant_management.tenant_business_database_workspace_anchor_task where task_id=?",
						String.class, workspaceAnchorTaskId)).isEqualTo("READY");
				assertThat(jdbc.queryForObject("select attempt_count from tenant_management.tenant_business_database_workspace_anchor_task where task_id=?",
						Integer.class, workspaceAnchorTaskId)).isEqualTo(readyAnchorAttempts);
				assertThat(jdbc.queryForObject("select version from tenant_management.tenant_business_database_workspace_anchor_task where task_id=?",
						Long.class, workspaceAnchorTaskId)).isEqualTo(readyAnchorVersion);
				assertThat(jdbc.queryForObject("select lifecycle_state from tenant_management.tenant_business_database_binding where tenant_id=?",
						String.class, tenantId)).isEqualTo("READY");
				assertThat(jdbc.queryForObject("select version from tenant_management.tenant_business_database_binding where tenant_id=?",
						Long.class, tenantId)).isEqualTo(readyBindingVersion);
				assertThat(dockerResourceExists("volume", volumeName)).isTrue();
				assertThat(Files.exists(tenantStateDirectory)).isFalse();
				assertThat(Files.isDirectory(parkedTenantStateDirectory)).isTrue();
			} finally {
				if (Files.exists(parkedTenantStateDirectory) && !Files.exists(tenantStateDirectory)) {
					Files.move(parkedTenantStateDirectory, tenantStateDirectory);
				}
			}
			UUID databaseIdentity = jdbc.queryForObject(
					"select database_identity from tenant_management.tenant_business_database_binding where tenant_id=?",
					UUID.class, tenantId);
			UUID existingTenantId = UUID.fromString(tenantId());
			UUID existingDatabaseIdentity = jdbc.queryForObject(
					"select database_identity from tenant_management.tenant_business_database_binding where tenant_id=?",
					UUID.class, existingTenantId);
			assertThat(databaseIdentity).isNotEqualTo(existingDatabaseIdentity);

			String credentialReference = jdbc.queryForObject("select credential_secret_reference from tenant_management.tenant_business_database_binding where tenant_id=?",
					String.class, tenantId);
			try (AutoCloseable ignored = TenantBusinessIntegrationFixture.registerLocalCredentials(
					credentialReference, localStateDirectory)) {
				String ownerToken = signIn(OWNER_EMAIL, workspaceSlug);
				String invitationEmail = WAREHOUSE_EMAIL;
				MvcResult invitation = mockMvc.perform(post("/api/v1/organization-invitations")
						.header("Authorization", "Bearer " + ownerToken)
						.header("Idempotency-Key", "activation-worker-warehouse-" + compactUuid())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + invitationEmail
								+ "\",\"displayName\":\"Integration Warehouse\",\"roles\":[\"WAREHOUSE\"]}"))
					.andReturn();
				assertThat(invitation.getResponse().getStatus()).as("the owner should invite an authorized Warehouse member")
						.isEqualTo(201);
				String invitationPayload = jdbc.queryForObject("""
						SELECT payload_ciphertext FROM iam.security_notification_outbox
						 WHERE notification_type='ORGANIZATION_INVITATION' AND recipient=?
						 ORDER BY created_at DESC LIMIT 1
						""", String.class, invitationEmail);
				String invitationToken = notificationOutbox.decrypt(invitationPayload).lines()
						.filter(line -> line.startsWith("token=")).map(line -> line.substring("token=".length()))
						.findFirst().orElseThrow(() -> new IllegalStateException("The test invitation token is unavailable"));
				MvcResult invitationAccepted = mockMvc.perform(post("/api/v1/organization-invitation-acceptances")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"" + invitationToken + "\",\"password\":\"" + TEST_PASSWORD
								+ "\",\"displayName\":\"Integration Warehouse\"}"))
					.andReturn();
				assertThat(invitationAccepted.getResponse().getStatus())
					.as("an existing warehouse user should accept the activated Tenant invitation")
					.isEqualTo(201);
				String warehouseToken = signIn(invitationEmail, workspaceSlug);
				String warehouseCode = "WH-" + compactUuid().substring(0, 12).toUpperCase();
				MvcResult createdWarehouse = mockMvc.perform(post("/api/v1/warehouses")
						.header("Authorization", "Bearer " + warehouseToken).contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + warehouseCode
								+ "\",\"name\":\"Provisioned tenant warehouse\",\"address\":\"Lima\"}"))
					.andReturn();
				assertThat(createdWarehouse.getResponse().getStatus())
					.as("the WAREHOUSE role should authorize a Tenant-routed warehouse write")
					.isEqualTo(201);
				UUID warehouseId = UUID.fromString(responseJson(createdWarehouse).get("id").asText());

				Path credentialFile = tenantStateDirectory.resolve("credentials.properties");
				String jdbcUrl = readProperty(credentialFile, "jdbc-url");
				String existingCredentialReference = TenantBusinessIntegrationFixture.credentialReference(existingTenantId);
				assertThat(jdbcUrl).isNotEqualTo(
						TenantBusinessIntegrationFixture.credentials(existingCredentialReference).jdbcUrl());
				String bootstrapPassword = Files.readString(tenantStateDirectory.resolve("bootstrap-admin-password"),
						StandardCharsets.UTF_8).strip();
				try (var connection = DriverManager.getConnection(jdbcUrl, "nexa_tenant_bootstrap_admin", bootstrapPassword);
					 var query = connection.prepareStatement("""
						 SELECT tenant_id, workspace_id, code FROM warehouse.warehouse WHERE id=?
						 """)) {
					query.setObject(1, warehouseId);
					try (var row = query.executeQuery()) {
						assertThat(row.next()).isTrue();
						assertThat(row.getObject("tenant_id", UUID.class)).isEqualTo(tenantId);
						assertThat(row.getObject("workspace_id", UUID.class)).isEqualTo(workspaceId);
						assertThat(row.getString("code")).isEqualTo(warehouseCode);
						assertThat(row.next()).isFalse();
					}
					try (var identity = connection.prepareStatement("""
							SELECT tenant_id, database_identity FROM nexa_platform.tenant_business_database_identity
							 WHERE singleton=TRUE
							"""); var row = identity.executeQuery()) {
						assertThat(row.next()).isTrue();
						assertThat(row.getObject("tenant_id", UUID.class)).isEqualTo(tenantId);
						assertThat(row.getObject("database_identity", UUID.class)).isEqualTo(databaseIdentity);
						assertThat(row.next()).isFalse();
					}
					try (var anchor = connection.prepareStatement("""
							SELECT count(*) FROM nexa_platform.tenant_workspace_scope_anchor
							 WHERE tenant_id=? AND workspace_id=?
							""")) {
						anchor.setObject(1, tenantId);
						anchor.setObject(2, workspaceId);
						try (var row = anchor.executeQuery()) {
							assertThat(row.next()).isTrue();
							assertThat(row.getInt(1)).isEqualTo(1);
						}
					}
				}
				assertThat(tenantJdbc().queryForObject("select count(*) from warehouse.warehouse where id=?",
						Integer.class, warehouseId)).isZero();
				MvcResult crossTenantRead = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.get("/api/v1/warehouses/" + warehouseId)
						.header("Authorization", "Bearer " + accessToken(WAREHOUSE_EMAIL, "PLATFORM")))
					.andReturn();
				assertThat(crossTenantRead.getResponse().getStatus())
						.as("a warehouse in a different physical Tenant database must not be visible")
						.isEqualTo(404);
			}
		} finally {
			try {
				if (Files.exists(parkedTenantStateDirectory) && !Files.exists(tenantStateDirectory)) {
					Files.move(parkedTenantStateDirectory, tenantStateDirectory);
				}
			} finally {
				Path cleanupRoot = Files.isDirectory(tenantStateDirectory) ? localStateDirectory : parkedStateRoot;
				stopTenantDatabase(cleanupRoot, tenantId);
			}
		}
	}

	private static ProcessResult runWorkerOnce(Path temporaryDirectory, Path centralConfig, Path localStateDirectory,
			UUID taskId) throws Exception {
		return runWorkerOnce(temporaryDirectory, centralConfig, localStateDirectory, taskId, false);
	}

	private static ProcessResult runWorkerOnce(Path temporaryDirectory, Path centralConfig, Path localStateDirectory,
			UUID taskId, boolean includeWorkspaceAnchor) throws Exception {
		Path log = temporaryDirectory.resolve("worker-once-" + taskId + ".log");
		writePrivateFile(log, "");
		List<String> command = new java.util.ArrayList<>(List.of("bash", WORKER.toString(), "--once", "--task-id",
				taskId.toString()));
		if (includeWorkspaceAnchor) command.add("--include-workspace-anchor");
		command.addAll(List.of("--central-configfile", centralConfig.toString(), "--local-state-dir",
				localStateDirectory.toString()));
		ProcessBuilder builder = new ProcessBuilder(command).directory(REPOSITORY_ROOT.toFile())
				.redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().put("NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD",
				CentralMigrationTestBootstrap.PROVISIONER_PASSWORD);
		builder.environment().put("NEXA_LOCAL_API_CONTAINER_NAME", apiContainerName);
		builder.environment().put("NEXA_LOCAL_API_COMPOSE_PROJECT", apiComposeProject);
		Process process = builder.start();
		Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
		Throwable primaryFailure = null;
		boolean interrupted = false;
		try {
			while (process.isAlive() && System.nanoTime() < deadline) {
				captureDescendants(process, descendants);
				process.waitFor(50, TimeUnit.MILLISECONDS);
			}
			captureDescendants(process, descendants);
			if (process.isAlive()) {
				throw new IllegalStateException("The one-shot Tenant provisioning worker timed out");
			}
			String output = Files.readString(log, StandardCharsets.UTF_8);
			return new ProcessResult(process.exitValue(), output);
		} catch (InterruptedException exception) {
			primaryFailure = exception;
			interrupted = true;
			throw exception;
		} catch (Exception | Error exception) {
			primaryFailure = exception;
			throw exception;
		} finally {
			try {
				stopWorkerProcess(process, descendants);
			} catch (RuntimeException | Error cleanupFailure) {
				if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
				else throw cleanupFailure;
			} finally {
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
	}

	private static void captureDescendants(Process process, Map<Long, ProcessHandle> descendants) {
		process.toHandle().descendants().forEach(handle -> descendants.put(handle.pid(), handle));
	}

	private static void stopWorkerProcess(Process process, Map<Long, ProcessHandle> descendants) {
		captureDescendants(process, descendants);
		if (process.isAlive()) process.destroy();
		descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
		long gracefulDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		boolean interrupted = false;
		while (process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive)) {
			captureDescendants(process, descendants);
			if (System.nanoTime() >= gracefulDeadline) break;
			try {
				Thread.sleep(50);
			} catch (InterruptedException exception) {
				interrupted = true;
				break;
			}
		}
		if (process.isAlive()) process.destroyForcibly();
		descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
		long forcedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		try {
			while (process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive)) {
				if (System.nanoTime() >= forcedDeadline) {
					throw new IllegalStateException("The one-shot Tenant worker process tree could not be stopped");
				}
				try {
					Thread.sleep(50);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	private static void stopTenantDatabase(Path stateRoot, UUID tenantId) throws Exception {
		Path composeEnvironment = stateRoot.resolve(tenantId.toString()).resolve("compose.env");
		if (!Files.isRegularFile(composeEnvironment)) return;
		String networkName = "nexa-tenant-business-" + tenantId;
		if (dockerResourceExists("network", networkName) && apiContainerName != null) {
			Process disconnect = new ProcessBuilder("docker", "network", "disconnect", "--force",
					networkName, apiContainerName).redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			if (!disconnect.waitFor(30, TimeUnit.SECONDS)) {
				disconnect.destroyForcibly();
				throw new IllegalStateException("Disposable Tenant API network detachment timed out");
			}
		}
		Process process = new ProcessBuilder("docker", "compose", "--project-name", "nexa-tenant-db-" + tenantId,
				"--env-file", composeEnvironment.toString(), "-f", TENANT_COMPOSE.toString(), "down", "-v",
				"--remove-orphans").redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		if (!process.waitFor(60, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("Disposable Tenant database cleanup timed out");
		}
		if (process.exitValue() != 0 || dockerResourceExists("volume", "nexa-tenant-business-" + tenantId)
				|| dockerResourceExists("network", networkName)) {
			throw new IllegalStateException("Disposable Tenant database resources were not removed");
		}
	}

	private static boolean dockerResourceExists(String kind, String name) throws Exception {
		Process process = new ProcessBuilder("docker", kind, "inspect", name)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("Disposable Tenant Docker resource inspection timed out");
		}
		return process.exitValue() == 0;
	}

	private static String readProperty(Path path, String key) throws Exception {
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			int separator = line.indexOf('=');
			if (separator > 0 && line.substring(0, separator).equals(key)) return line.substring(separator + 1);
		}
		throw new IllegalStateException("Private Tenant state is missing a required value");
	}

	private static tools.jackson.databind.JsonNode responseJson(MvcResult result) throws Exception {
		return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
	}

	private String signIn(String email, String workspaceSlug) throws Exception {
		MvcResult response = mockMvc.perform(withBrowserCsrf(post("/api/v1/authentication/sign-in")
					.header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
					.content("{\"identifier\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD
							+ "\",\"workspaceSlug\":\"" + workspaceSlug + "\",\"surface\":\"PLATFORM\"}")))
				.andReturn();
		assertThat(response.getResponse().getStatus()).as("authorized integration user should sign in").isEqualTo(200);
		return responseJson(response).get("accessToken").asText();
	}

	private static void writePrivateFile(Path path, String content) throws Exception {
		Files.writeString(path, content, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(path, PRIVATE_FILE_PERMISSIONS);
	}

	private static String compactUuid() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private record ProcessResult(int exitCode, String output) { }

	private static final class OrganizationRegistrationPayload {
		private static String forFounder(String founderEmail, String workspaceSlug) {
			return "{\"legalName\":\"Integration Cold Chain\",\"displayName\":\"Integration Cold Chain\","
					+ "\"businessIdentifier\":\"IT-" + workspaceSlug + "\","
					+ "\"operationCategory\":\"b2bColdChainDistributor\",\"storageSiteName\":\"Integration Store\","
					+ "\"storageSiteAddress\":\"Lima\",\"founderEmail\":\"" + founderEmail + "\","
					+ "\"founderDisplayName\":\"Integration Founder\",\"workspaceName\":\"Integration Workspace\","
					+ "\"workspaceSlug\":\"" + workspaceSlug + "\",\"referencePlan\":\"Starter\","
					+ "\"termsVersion\":\"2026-01\",\"termsAccepted\":true}";
		}
	}

	private static String safeOutput(String output) {
		String sanitized = output.replaceAll("(?i)(password|secret|token)(\\s*[=:]\\s*)[^\\s,;]+",
				"$1$2[redacted]");
		return sanitized.length() <= 2_000 ? sanitized : sanitized.substring(sanitized.length() - 2_000);
	}
}
