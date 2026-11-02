package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves an opaque central reference to one owner-private mounted runtime secret. */
public final class CloudTenantBusinessDatabaseCredentialsProvider implements TenantBusinessDatabaseCredentialsProvider {
	private static final String REFERENCE_PREFIX = "cloud-tenant-db:";
	private static final String REFERENCE_PATTERN = "cloud-tenant-db:[a-z0-9][a-z0-9_-]{31,127}";
	private static final Set<String> KEYS = Set.of("credential-secret-reference", "jdbc-url", "username", "password");
	private final Path directory;

	public CloudTenantBusinessDatabaseCredentialsProvider(Path directory) {
		this.directory = Objects.requireNonNull(directory, "Cloud runtime secret directory is required")
				.toAbsolutePath().normalize();
		CloudTenantDatabaseSecretFiles.requireDirectory(this.directory);
	}

	@Override
	public TenantBusinessDatabaseCredentials requireCredentials(String reference) {
		if (reference == null || !reference.matches(REFERENCE_PATTERN)) throw CloudTenantDatabaseSecretFiles.unavailable();
		Map<String, String> properties = CloudTenantDatabaseSecretFiles.read(directory,
				"tenant-runtime-" + reference.substring(REFERENCE_PREFIX.length()) + ".properties", KEYS);
		if (!reference.equals(properties.get("credential-secret-reference"))
				|| !"nexa_runtime".equals(properties.get("username"))) throw CloudTenantDatabaseSecretFiles.unavailable();
		CloudTenantDatabaseSecretFiles.requireNeonJdbcUrl(properties.get("jdbc-url"));
		return new TenantBusinessDatabaseCredentials(properties.get("jdbc-url"), properties.get("username"),
				properties.get("password"));
	}
}
