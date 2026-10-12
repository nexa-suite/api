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

/** Resolves the isolated local recharge worker secret from an owner-only Tenant state file. */
public final class LocalTenantWalletRechargeWorkerCredentialsProvider
        implements TenantBusinessDatabaseCredentialsProvider {
    private static final String REFERENCE_PREFIX = "local-tenant-wallet-recharge-worker:";
    private static final String WORKER_ROLE = "nexa_wallet_recharge_worker";
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "credential-secret-reference", "jdbc-url", "api-jdbc-url", "username", "password");

    private final Path registryDirectory;

    public LocalTenantWalletRechargeWorkerCredentialsProvider(Path registryDirectory) {
        this.registryDirectory = Objects.requireNonNull(registryDirectory)
                .toAbsolutePath().normalize();
    }

    @Override
    public TenantBusinessDatabaseCredentials requireCredentials(String credentialSecretReference) {
        UUID tenantId = parseReference(credentialSecretReference);
        try {
            LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(registryDirectory);
            Path tenantDirectory = registryDirectory.resolve(tenantId.toString()).normalize();
            if (!tenantDirectory.getParent().equals(registryDirectory)) throw unavailable();
            LocalTenantBusinessDatabaseCredentialsProvider.requirePrivateDirectory(tenantDirectory);
            Path workerFile = tenantDirectory.resolve("wallet-recharge-worker.properties");
            if (!Files.exists(workerFile, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            Map<String, String> properties = LocalTenantBusinessDatabaseCredentialsProvider
                    .readPrivateProperties(workerFile);
            if (!REQUIRED_KEYS.equals(properties.keySet())
                    || !credentialSecretReference.equals(properties.get("credential-secret-reference"))) {
                throw new IllegalStateException("Local Tenant wallet worker credential file has an unexpected format");
            }
            if (!WORKER_ROLE.equals(properties.get("username"))) {
                throw new IllegalStateException("Local Tenant wallet worker credentials require the dedicated role");
            }
            LocalTenantBusinessDatabaseCredentialsProvider.requireLoopbackJdbcUrl(properties.get("jdbc-url"));
            String jdbcUrl = properties.get("api-jdbc-url");
            LocalTenantBusinessDatabaseCredentialsProvider.requireTenantNetworkJdbcUrl(jdbcUrl, tenantId.toString());
            return new TenantBusinessDatabaseCredentials(jdbcUrl, WORKER_ROLE, properties.get("password"));
        } catch (IOException | UnsupportedOperationException exception) {
            throw new IllegalStateException("Local Tenant wallet recharge worker credentials are unavailable or not private",
                    exception);
        }
    }

    private static UUID parseReference(String reference) {
        if (reference == null || !reference.matches(
                "local-tenant-wallet-recharge-worker:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw unavailable();
        }
        try {
            return UUID.fromString(reference.substring(REFERENCE_PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("No unique local Tenant wallet recharge worker credentials are configured");
    }
}
