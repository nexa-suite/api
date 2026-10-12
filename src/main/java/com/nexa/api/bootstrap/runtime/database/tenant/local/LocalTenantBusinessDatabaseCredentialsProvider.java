package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Resolves local-only Tenant runtime credentials from owner-private per-Tenant files. */
public final class LocalTenantBusinessDatabaseCredentialsProvider
		implements TenantBusinessDatabaseCredentialsProvider {
	private static final String TENANT_DIRECTORY = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String LOCAL_CREDENTIAL_REFERENCE = "local-tenant-db:[A-Za-z0-9][A-Za-z0-9._-]{0,127}";
	private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
	private static final Set<String> CREDENTIAL_KEYS = Set.of(
			"credential-secret-reference", "jdbc-url", "api-jdbc-url", "username", "password");

	private final Path registryDirectory;

	public LocalTenantBusinessDatabaseCredentialsProvider(Path registryDirectory) {
		this.registryDirectory = Objects.requireNonNull(registryDirectory,
				"Local Tenant database credential directory is required").toAbsolutePath().normalize();
	}

	@Override
	public TenantBusinessDatabaseCredentials requireCredentials(String credentialSecretReference) {
		CredentialFile credentialFile = requireCredentialProperties(credentialSecretReference);
		Map<String, String> credentials = credentialFile.properties();
		String jdbcUrl = credentials.get("api-jdbc-url");
		requireTenantNetworkJdbcUrl(jdbcUrl, credentialFile.tenantId());
		return new TenantBusinessDatabaseCredentials(jdbcUrl, credentials.get("username"), credentials.get("password"));
	}

	/** Host-only provisioner verification uses loopback; API pools must use the private Tenant network alias. */
	public TenantBusinessDatabaseCredentials requireHostCredentials(String credentialSecretReference) {
		Map<String, String> credentials = requireCredentialProperties(credentialSecretReference).properties();
		String jdbcUrl = credentials.get("jdbc-url");
		requireLoopbackJdbcUrl(jdbcUrl);
		return new TenantBusinessDatabaseCredentials(jdbcUrl, credentials.get("username"), credentials.get("password"));
	}

	private CredentialFile requireCredentialProperties(String credentialSecretReference) {
		if (credentialSecretReference == null || !credentialSecretReference.matches(LOCAL_CREDENTIAL_REFERENCE)) {
			throw missingCredentials();
		}
		try {
			requirePrivateDirectory(registryDirectory);
		CredentialFile credentials = null;
			try (DirectoryStream<Path> tenantDirectories = Files.newDirectoryStream(registryDirectory)) {
				for (Path tenantDirectory : tenantDirectories) {
					if (!tenantDirectory.getFileName().toString().matches(TENANT_DIRECTORY)) continue;
					requirePrivateDirectory(tenantDirectory);
					Path credentialFile = tenantDirectory.resolve("credentials.properties");
					if (!Files.exists(credentialFile, LinkOption.NOFOLLOW_LINKS)) continue;
					Map<String, String> properties = readPrivateProperties(credentialFile);
					if (!CREDENTIAL_KEYS.equals(properties.keySet())) {
						throw new IllegalStateException("Local Tenant credential file has an unexpected format");
					}
					if (!credentialSecretReference.equals(properties.get("credential-secret-reference"))) continue;
					if (credentials != null) throw new IllegalStateException("Local Tenant credential reference is duplicated");
					credentials = new CredentialFile(tenantDirectory.getFileName().toString(), properties);
				}
			}
			if (credentials == null) throw missingCredentials();
			Map<String, String> properties = credentials.properties();
			if (!"nexa_runtime".equals(properties.get("username"))) {
				throw new IllegalStateException("Local Tenant credentials must use the runtime role");
			}
			requireLoopbackJdbcUrl(properties.get("jdbc-url"));
			requireTenantNetworkJdbcUrl(properties.get("api-jdbc-url"), credentials.tenantId());
			return credentials;
		} catch (IOException | UnsupportedOperationException exception) {
			throw new IllegalStateException("Local Tenant database credentials are unavailable or not private", exception);
		}
	}

	static void requireTenantNetworkJdbcUrl(String jdbcUrl, String tenantId) {
		try {
			if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) throw invalidTenantNetworkUrl();
			URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
			if (!("tenant-db-" + tenantId).equals(uri.getHost()) || uri.getPort() != 5432
					|| uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
					|| !"/nexa_tenant_business".equals(uri.getPath())) {
				throw invalidTenantNetworkUrl();
			}
		} catch (IllegalArgumentException exception) {
			throw invalidTenantNetworkUrl();
		}
	}

	static Map<String, String> readPrivateProperties(Path file) throws IOException {
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("Local Tenant credential file must be a regular file");
		}
		if (!Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_FILE_PERMISSIONS)) {
			throw new IllegalStateException("Local Tenant credential file permissions must be owner read/write only");
		}
		StrictProperties loaded = new StrictProperties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			loaded.load(reader);
		}
		Map<String, String> result = new java.util.HashMap<>();
		for (String key : loaded.stringPropertyNames()) {
			String value = loaded.getProperty(key);
			if (value == null || value.isBlank()) {
				throw new IllegalStateException("Local Tenant credential properties must be non-empty");
			}
			result.put(key, value);
		}
		return Map.copyOf(result);
	}

	static void requirePrivateDirectory(Path directory) throws IOException {
		if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("Local Tenant credential directories must be real directories");
		}
		if (!Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
				.equals(PRIVATE_DIRECTORY_PERMISSIONS)) {
			throw new IllegalStateException("Local Tenant credential directory permissions must be owner-only");
		}
	}

	static void requireLoopbackJdbcUrl(String jdbcUrl) {
		try {
			if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) throw invalidLocalUrl();
			URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
			String host = uri.getHost();
			if (!"127.0.0.1".equals(host)
					|| uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null
					|| uri.getRawQuery() != null || uri.getRawFragment() != null
					|| !"/nexa_tenant_business".equals(uri.getPath())) {
				throw invalidLocalUrl();
			}
		} catch (IllegalArgumentException exception) {
			throw invalidLocalUrl();
		}
	}

	private static IllegalStateException missingCredentials() {
		return new IllegalStateException("No unique local Tenant runtime credentials are configured for this reference");
	}

	private static IllegalStateException invalidLocalUrl() {
		return new IllegalStateException("Local Tenant credentials must target the loopback Tenant database");
	}

	private static IllegalStateException invalidTenantNetworkUrl() {
		return new IllegalStateException("Local API Tenant credentials must use the UUID-bound Tenant network alias");
	}

	private static final class StrictProperties extends Properties {
		@Override
		public synchronized Object put(Object key, Object value) {
			if (containsKey(key)) throw new IllegalStateException("Local Tenant credential file contains a duplicate key");
			return super.put(key, value);
		}
	}

	private record CredentialFile(String tenantId, Map<String, String> properties) { }
}
