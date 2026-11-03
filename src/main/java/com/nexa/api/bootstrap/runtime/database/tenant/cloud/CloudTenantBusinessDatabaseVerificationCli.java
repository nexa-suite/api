package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseReadinessVerifier;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verify-only deployment adapter. Never migrates, grants privileges or changes central bindings. */
public final class CloudTenantBusinessDatabaseVerificationCli {
    private static final Set<String> KEYS = Set.of("tenant-id", "workspace-id", "database-identity",
            "credential-secret-reference", "jdbc-url", "runtime-password", "policy-writer-password",
            "migrator-password", "central-jdbc-url", "central-runtime-password", "central-migrator-password");

    private CloudTenantBusinessDatabaseVerificationCli() { }

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !args[0].startsWith("--config=")) throw unavailable();
            Path path = Path.of(args[0].substring("--config=".length()));
            if (!path.isAbsolute() || path.getParent() == null) throw unavailable();
            Map<String, String> values = CloudTenantDatabaseSecretFiles.read(path.getParent(),
                    path.getFileName().toString(), KEYS);
            String digest = verify(values);
            System.out.println("Tenant database checks passed; schema-manifest-sha256=" + digest
                    + "; central binding not verified; no database changes performed.");
        } catch (Exception | LinkageError failure) {
            // JDBC/Flyway exception messages can contain connection material. Never print causes or inputs.
            System.err.println("Cloud Tenant verification failed; connection material and diagnostics withheld.");
            System.exit(1);
        }
    }

    static String verify(Map<String, String> values) throws Exception {
        validateConfiguration(values);
        UUID tenant = UUID.fromString(values.get("tenant-id"));
        UUID workspace = UUID.fromString(values.get("workspace-id"));
        UUID database = UUID.fromString(values.get("database-identity"));
        var requirements = TenantBusinessDatabaseMigrationRequirements.load();
        requirements.verifyRequiredSqlAssets();
        String url = values.get("jdbc-url");
        try (Connection migrator = connection(url, "nexa_migrator", values.get("migrator-password"))) {
            TenantBusinessDatabaseReadinessVerifier.verifyIdentityAndWorkspace(migrator, tenant, workspace, database);
        }
        Flyway flyway = Flyway.configure().dataSource(url, "nexa_migrator", values.get("migrator-password"))
                .locations("classpath:db/tenant-migration").cleanDisabled(true).loggers(SilentLogCreator.class.getName()).load();
        flyway.validate();
        Set<String> applied = new HashSet<>();
        for (var migration : flyway.info().applied()) {
            if (migration.getVersion() == null || migration.getState() != MigrationState.SUCCESS) throw unavailable();
            applied.add(migration.getVersion().getVersion());
        }
        requirements.verifyRequiredMigrations(applied);
        if (!applied.equals(requirements.requiredVersions())) throw unavailable();
        try (Connection runtime = connection(url, "nexa_runtime", values.get("runtime-password"))) {
            TenantBusinessDatabaseReadinessVerifier.verifyNeonRuntimeRole(runtime);
            TenantBusinessDatabaseReadinessVerifier.verifyIdentityAndWorkspace(runtime, tenant, workspace, database);
        }
        try (Connection writer = connection(url, "nexa_policy_snapshot_writer", values.get("policy-writer-password"))) {
            TenantBusinessDatabaseReadinessVerifier.verifyNeonPolicySnapshotWriterRole(writer);
            TenantBusinessDatabaseReadinessVerifier.verifyIdentityAndWorkspace(writer, tenant, workspace, database);
        }
        return requirements.schemaManifestDigest();
    }

    private static Connection connection(String url, String role, String password) throws Exception {
        Connection connection = DriverManager.getConnection(url, role, password);
        try {
            connection.setReadOnly(true);
            if (!role.equals(connection.getMetaData().getUserName())
                    || !"nexa_tenant_business".equals(connection.getCatalog())) throw unavailable();
            return connection;
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
    }

    static void validateConfiguration(Map<String, String> values) {
        try {
            if (!values.keySet().equals(KEYS) || values.values().stream().anyMatch(v -> v == null || v.isBlank()
                    || v.length() > 4096 || !v.equals(v.strip()))) throw unavailable();
            UUID.fromString(values.get("tenant-id"));
            UUID.fromString(values.get("workspace-id"));
            UUID.fromString(values.get("database-identity"));
            if (!values.get("credential-secret-reference").matches("cloud-tenant-db:[a-z0-9][a-z0-9_-]{31,127}")) {
                throw unavailable();
            }
            CloudTenantDatabaseSecretFiles.requireNeonJdbcUrl(values.get("jdbc-url"));
            URI central = URI.create(values.get("central-jdbc-url").substring("jdbc:".length()));
            URI tenant = URI.create(values.get("jdbc-url").substring("jdbc:".length()));
            if (!values.get("central-jdbc-url").startsWith("jdbc:postgresql://") || central.getHost() == null
                    || !central.getHost().matches("ep-[a-z0-9-]+\\.(c-[0-9]+\\.)?[a-z0-9-]+\\.(aws|azure)\\.neon\\.tech")
                    || central.getHost().substring(0, central.getHost().indexOf('.')).endsWith("-pooler")
                    || central.getUserInfo() != null || central.getFragment() != null
                    || central.getHost().equals(tenant.getHost())) throw unavailable();
            Set<String> passwords = new HashSet<>();
            for (String key : Set.of("runtime-password", "policy-writer-password", "migrator-password",
                    "central-runtime-password", "central-migrator-password")) {
                if (!passwords.add(values.get(key))) throw unavailable();
            }
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    /** Explicit Flyway logger for this standalone verification process; never emits connection material. */
    public static final class SilentLogCreator implements LogCreator {
        @Override
        public Log createLogger(Class<?> type) {
            return new Log() {
                @Override public void debug(String message) { }
                @Override public void info(String message) { }
                @Override public void warn(String message) { }
                @Override public void error(String message) { }
                @Override public void error(String message, Exception failure) { }
                @Override public void notice(String message) { }
            };
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Cloud Tenant verification inputs unavailable");
    }
}
