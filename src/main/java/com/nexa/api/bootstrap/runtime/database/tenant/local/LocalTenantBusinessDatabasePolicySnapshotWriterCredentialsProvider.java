package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Resolves only the separate local policy-snapshot writer secret for one Tenant database identity. */
public final class LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider
		implements TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider {
	private static final Set<String> STATE_KEYS = Set.of(
			"tenant-id", "workspace-id", "database-identity", "credential-secret-reference", "database-port");
	private static final Set<String> WRITER_CREDENTIAL_KEYS = Set.of("jdbc-url", "api-jdbc-url", "username", "password");
	private static final String WRITER_ROLE = "nexa_policy_snapshot_writer";

	private final Path registryDirectory;

	public LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(Path registryDirectory) {
		this.registryDirectory = Objects.requireNonNull(registryDirectory,
				"Local Tenant database credential directory is required").toAbsolutePath().normalize();
	}

	@Override
	public TenantBusinessDatabaseCredentials requireWriterCredentials(TenantId tenantId, UUID databaseIdentity) {
		return requireWriterCredentials(tenantId, databaseIdentity, false);
	}

	/** Host-only provisioner verification keeps using the loopback port; application writes use the private alias. */
	public TenantBusinessDatabaseCredentials requireHostWriterCredentials(TenantId tenantId, UUID databaseIdentity) {
		return requireWriterCredentials(tenantId, databaseIdentity, true);
	}

	private TenantBusinessDatabaseCredentials requireWriterCredentials(TenantId tenantId, UUID databaseIdentity,
			boolean hostOnly) {
		Objects.requireNonNull(tenantId, "Tenant id is required");
		Objects.requireNonNull(databaseIdentity, "Tenant database identity is required");
		try {
			LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(registryDirectory);
			Path tenantDirectory = registryDirectory.resolve(tenantId.toString());
			LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(tenantDirectory);
			Map<String, String> state = LocalTenantBusinessDatabaseCredentialsProvider
					.readPrivateProperties(tenantDirectory.resolve("state.properties"));
			if (!STATE_KEYS.equals(state.keySet()) || !tenantId.toString().equals(state.get("tenant-id"))
					|| !databaseIdentity.toString().equals(state.get("database-identity"))) {
				throw new IllegalStateException("Local policy-snapshot writer state does not match its Tenant database identity");
			}

			Map<String, String> credentials = LocalTenantBusinessDatabaseCredentialsProvider
					.readPrivateProperties(tenantDirectory.resolve("policy-snapshot-writer.properties"));
			if (!WRITER_CREDENTIAL_KEYS.equals(credentials.keySet())) {
				throw new IllegalStateException("Local policy-snapshot writer file has an unexpected format");
			}
			if (!WRITER_ROLE.equals(credentials.get("username"))) {
				throw new IllegalStateException("Local policy snapshots require the dedicated writer role");
			}
			String hostJdbcUrl = credentials.get("jdbc-url");
			LocalTenantBusinessDatabaseCredentialsProvider.requireLoopbackJdbcUrl(hostJdbcUrl);
			if (!("jdbc:postgresql://127.0.0.1:" + state.get("database-port")
					+ "/nexa_tenant_business").equals(hostJdbcUrl)) {
				throw new IllegalStateException("Local policy-snapshot writer target differs from its Tenant database state");
			}
			String apiJdbcUrl = credentials.get("api-jdbc-url");
			LocalTenantBusinessDatabaseCredentialsProvider.requireTenantNetworkJdbcUrl(apiJdbcUrl, tenantId.toString());
			String jdbcUrl = hostOnly ? hostJdbcUrl : apiJdbcUrl;
			return new TenantBusinessDatabaseCredentials(jdbcUrl, credentials.get("username"),
					credentials.get("password"));
		} catch (IOException | UnsupportedOperationException | IllegalStateException exception) {
			throw new TenantBusinessDatabasePolicySnapshotWriterUnavailableException();
		}
	}
}
