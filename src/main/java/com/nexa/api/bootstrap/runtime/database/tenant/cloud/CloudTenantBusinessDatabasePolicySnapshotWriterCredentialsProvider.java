package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Resolves a separate policy-writer secret for the exact authoritative Tenant/database pair. */
public final class CloudTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider
		implements TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider {
	private static final Set<String> KEYS = Set.of("tenant-id", "database-identity", "jdbc-url", "username", "password");
	private final Path directory;

	public CloudTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(Path directory) {
		this.directory = Objects.requireNonNull(directory, "Cloud policy-writer secret directory is required")
				.toAbsolutePath().normalize();
		CloudTenantDatabaseSecretFiles.requireDirectory(this.directory);
	}

	@Override
	public TenantBusinessDatabaseCredentials requireWriterCredentials(TenantId tenantId, UUID databaseIdentity) {
		Objects.requireNonNull(tenantId, "Verified Tenant id is required");
		Objects.requireNonNull(databaseIdentity, "Verified Tenant database identity is required");
		try {
			Map<String, String> properties = CloudTenantDatabaseSecretFiles.read(directory,
					"tenant-policy-writer-" + tenantId + "-" + databaseIdentity + ".properties", KEYS);
			if (!tenantId.toString().equals(properties.get("tenant-id"))
					|| !databaseIdentity.toString().equals(properties.get("database-identity"))
					|| !"nexa_policy_snapshot_writer".equals(properties.get("username"))) {
				throw CloudTenantDatabaseSecretFiles.unavailable();
			}
			CloudTenantDatabaseSecretFiles.requireNeonJdbcUrl(properties.get("jdbc-url"));
			return new TenantBusinessDatabaseCredentials(properties.get("jdbc-url"), properties.get("username"),
					properties.get("password"));
		} catch (IllegalStateException exception) {
			throw new TenantBusinessDatabasePolicySnapshotWriterUnavailableException();
		}
	}
}
