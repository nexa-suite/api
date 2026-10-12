package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves only the dedicated per-Tenant Business Documents worker secret. */
public final class LocalTenantBusinessDocumentWorkerCredentialsProvider
		implements TenantBusinessDatabaseCredentialsProvider {
	private static final String REFERENCE_PREFIX = "local-tenant-business-documents-worker:";
	private static final String TENANT_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final Set<String> KEYS = Set.of(
			"credential-secret-reference", "jdbc-url", "api-jdbc-url", "username", "password");
	private final Path credentialDirectory;

	public LocalTenantBusinessDocumentWorkerCredentialsProvider(Path credentialDirectory) {
		this.credentialDirectory = Objects.requireNonNull(credentialDirectory,
				"Private Tenant credential directory is required").toAbsolutePath().normalize();
	}

	@Override
	public TenantBusinessDatabaseCredentials requireCredentials(String credentialSecretReference) {
		if (credentialSecretReference == null || !credentialSecretReference.matches(
				"local-tenant-business-documents-worker:" + TENANT_UUID)) {
			throw unavailable();
		}
		String tenantId = credentialSecretReference.substring(REFERENCE_PREFIX.length());
		try {
			LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(credentialDirectory);
			Path tenantDirectory = credentialDirectory.resolve(tenantId);
			LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(tenantDirectory);
			Path file = tenantDirectory.resolve("business-documents-worker.properties");
			if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw unavailable();
			}
			Map<String, String> values = LocalTenantBusinessDatabaseCredentialsProvider.readPrivateProperties(file);
			if (!KEYS.equals(values.keySet())
					|| !credentialSecretReference.equals(values.get("credential-secret-reference"))
					|| !"nexa_business_documents_worker".equals(values.get("username"))) {
				throw unavailable();
			}
			LocalTenantBusinessDatabaseCredentialsProvider.requireLoopbackJdbcUrl(values.get("jdbc-url"));
			LocalTenantBusinessDatabaseCredentialsProvider.requireTenantNetworkJdbcUrl(values.get("api-jdbc-url"), tenantId);
			return new TenantBusinessDatabaseCredentials(values.get("api-jdbc-url"),
					values.get("username"), values.get("password"));
		} catch (IOException | UnsupportedOperationException exception) {
			throw new IllegalStateException("Dedicated local Tenant document-worker credentials are unavailable or not private", exception);
		}
	}

	private static IllegalStateException unavailable() {
		return new IllegalStateException("Dedicated local Tenant document-worker credentials are unavailable");
	}
}
