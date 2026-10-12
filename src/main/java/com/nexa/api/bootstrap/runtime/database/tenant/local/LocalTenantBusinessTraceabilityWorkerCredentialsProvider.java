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
import java.util.UUID;

/** Resolves only the dedicated per-Tenant Business Traceability worker secret. */
public final class LocalTenantBusinessTraceabilityWorkerCredentialsProvider
        implements TenantBusinessDatabaseCredentialsProvider {
    private static final String REFERENCE_PREFIX = "local-tenant-business-traceability-worker:";
    private static final String WORKER_ROLE = "nexa_business_traceability_worker";
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "credential-secret-reference", "jdbc-url", "api-jdbc-url", "username", "password");
    private final Path credentialDirectory;

    public LocalTenantBusinessTraceabilityWorkerCredentialsProvider(Path credentialDirectory) {
        this.credentialDirectory = Objects.requireNonNull(credentialDirectory,
                "Private Tenant credential directory is required").toAbsolutePath().normalize();
    }

    @Override
    public TenantBusinessDatabaseCredentials requireCredentials(String credentialSecretReference) {
        UUID tenantId = parseReference(credentialSecretReference);
        try {
            LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(credentialDirectory);
            Path tenantDirectory = credentialDirectory.resolve(tenantId.toString()).normalize();
            if (!tenantDirectory.getParent().equals(credentialDirectory)) throw unavailable();
            LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(tenantDirectory);
            Path file = tenantDirectory.resolve("business-traceability-worker.properties");
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            Map<String, String> values = LocalTenantBusinessDatabaseCredentialsProvider.readPrivateProperties(file);
            if (!REQUIRED_KEYS.equals(values.keySet())
                    || !credentialSecretReference.equals(values.get("credential-secret-reference"))
                    || !WORKER_ROLE.equals(values.get("username"))) throw unavailable();
            LocalTenantBusinessDatabaseCredentialsProvider.requireLoopbackJdbcUrl(values.get("jdbc-url"));
            String jdbcUrl = values.get("api-jdbc-url");
            LocalTenantBusinessDatabaseCredentialsProvider.requireTenantNetworkJdbcUrl(jdbcUrl, tenantId.toString());
            return new TenantBusinessDatabaseCredentials(jdbcUrl, WORKER_ROLE, values.get("password"));
        } catch (IOException | UnsupportedOperationException exception) {
            throw new IllegalStateException(
                    "Dedicated local Tenant traceability-worker credentials are unavailable or not private", exception);
        }
    }

    private static UUID parseReference(String reference) {
        if (reference == null || !reference.matches(
                "local-tenant-business-traceability-worker:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw unavailable();
        }
        try {
            return UUID.fromString(reference.substring(REFERENCE_PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Local Tenant traceability-worker credentials are unavailable");
    }
}
