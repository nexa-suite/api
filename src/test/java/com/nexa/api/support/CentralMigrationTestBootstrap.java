package com.nexa.api.support;

import com.nexa.api.bootstrap.runtime.CentralDatabaseMigrationBootstrap;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.UUID;

/** Test-only invocation of the same central migration bootstrap used by Compose. */
public final class CentralMigrationTestBootstrap {
    private CentralMigrationTestBootstrap() { }

    public static MigrationCredentials migrateLatest(PostgreSQLContainer database) {
        return migrateLatest(database, "test-runtime-" + UUID.randomUUID());
    }

    public static MigrationCredentials migrateLatest(PostgreSQLContainer database, String runtimePassword) {
        String migratorPassword = "test-migrator-" + UUID.randomUUID();
        String workspaceScopeReaderPassword = "test-scope-reader-" + UUID.randomUUID();
        String businessDocumentsScopeReaderPassword = "test-documents-scope-reader-" + UUID.randomUUID();
        String internalOperatorPassword = "test-operator-" + UUID.randomUUID();
        try {
            CentralDatabaseMigrationBootstrap.migrateLatest(database.getJdbcUrl(), database.getUsername(),
                    database.getPassword(), runtimePassword, migratorPassword, workspaceScopeReaderPassword,
                    businessDocumentsScopeReaderPassword, internalOperatorPassword, Path.of("ops/database"));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not run disposable central database migrations", exception);
        }
        return new MigrationCredentials(migratorPassword, runtimePassword, workspaceScopeReaderPassword,
                businessDocumentsScopeReaderPassword, internalOperatorPassword);
    }

    public record MigrationCredentials(String migratorPassword, String runtimePassword, String workspaceScopeReaderPassword,
                                       String businessDocumentsScopeReaderPassword,
                                       String internalOperatorPassword) { }
}
