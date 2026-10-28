package com.nexa.api.support;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Test-only rehearsal of operator preparation followed by direct-login Flyway. */
public final class CentralMigrationTestBootstrap {
    private static final int LAST_ADMIN_MIGRATION = 168;

    private CentralMigrationTestBootstrap() { }

    public static MigrationCredentials migrateLatest(PostgreSQLContainer database) {
        String migratorPassword = "test-migrator-" + UUID.randomUUID();
        String workspaceScopeReaderPassword = "test-scope-reader-" + UUID.randomUUID();
        String businessDocumentsScopeReaderPassword = "test-documents-scope-reader-" + UUID.randomUUID();
        String internalOperatorPassword = "test-operator-" + UUID.randomUUID();
        prepareRoles(database, migratorPassword, workspaceScopeReaderPassword,
                businessDocumentsScopeReaderPassword, internalOperatorPassword);

        Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration").target(Integer.toString(LAST_ADMIN_MIGRATION)).load().migrate();

        List<Path> preparations;
        try (var scripts = Files.list(Path.of("ops/database"))) {
            preparations = scripts.filter(path -> path.getFileName().toString()
                            .matches("prepare-v[0-9]+-.+\\.sql"))
                    .filter(path -> preparationVersion(path) > LAST_ADMIN_MIGRATION)
                    .sorted(Comparator.comparingInt(CentralMigrationTestBootstrap::preparationVersion))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not locate central migration preparation scripts", exception);
        }

        int appliedVersion = LAST_ADMIN_MIGRATION;
        for (Path preparation : preparations) {
            int version = preparationVersion(preparation);
            if (version - 1 > appliedVersion) migrateThrough(database, migratorPassword, version - 1);
            applyPreparation(database, preparation);
            migrateThrough(database, migratorPassword, version);
            appliedVersion = version;
        }
        Flyway.configure().dataSource(database.getJdbcUrl(), "nexa_migrator", migratorPassword)
                .locations("classpath:db/migration").load().migrate();
        return new MigrationCredentials(migratorPassword, workspaceScopeReaderPassword,
                businessDocumentsScopeReaderPassword, internalOperatorPassword);
    }

    private static void prepareRoles(PostgreSQLContainer database, String migratorPassword,
                                     String workspaceScopeReaderPassword,
                                     String businessDocumentsScopeReaderPassword,
                                     String internalOperatorPassword) {
        try (Connection connection = adminConnection(database); Statement statement = connection.createStatement()) {
            ensureRole(statement, "nexa_runtime",
                    "LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'test-runtime-only'");
            statement.execute("CREATE ROLE nexa_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE "
                    + "NOREPLICATION NOBYPASSRLS PASSWORD '" + migratorPassword + "'");
            statement.execute("CREATE ROLE nexa_tenant_database_provisioner LOGIN NOINHERIT NOSUPERUSER "
                    + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_workspace_scope_reader NOLOGIN NOINHERIT NOSUPERUSER "
                    + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_workspace_scope_reader_login LOGIN NOINHERIT NOSUPERUSER "
                    + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + workspaceScopeReaderPassword + "'");
            statement.execute("GRANT nexa_workspace_scope_reader TO nexa_workspace_scope_reader_login "
                    + "WITH INHERIT FALSE, SET TRUE, ADMIN FALSE");
            statement.execute("CREATE ROLE nexa_internal_operator NOLOGIN NOINHERIT NOSUPERUSER "
                    + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_internal_operator_login LOGIN NOINHERIT NOSUPERUSER "
                    + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + internalOperatorPassword + "'");
            statement.execute("GRANT nexa_internal_operator TO nexa_internal_operator_login "
                    + "WITH INHERIT FALSE, SET TRUE, ADMIN FALSE");
            statement.execute("CREATE ROLE nexa_business_documents_scope_reader LOGIN NOINHERIT NOSUPERUSER "
                    + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + businessDocumentsScopeReaderPassword + "'");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not prepare disposable PostgreSQL migration roles", exception);
        }
    }

    private static void ensureRole(Statement statement, String role, String attributes) throws SQLException {
        boolean exists;
        boolean canLogin;
        try (var result = statement.executeQuery("SELECT rolcanlogin FROM pg_roles WHERE rolname='" + role + "'")) {
            exists = result.next();
            canLogin = exists && result.getBoolean(1);
        }
        if (!exists) {
            statement.execute("CREATE ROLE " + role + " " + attributes);
        } else {
            String restricted = "LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS";
            statement.execute("ALTER ROLE " + role + " " + restricted
                    + (canLogin ? "" : " PASSWORD 'test-runtime-only'"));
        }
    }

    private static int preparationVersion(Path preparation) {
        String name = preparation.getFileName().toString();
        int separator = name.indexOf('-', "prepare-v".length());
        return Integer.parseInt(name.substring("prepare-v".length(), separator));
    }

    private static void migrateThrough(PostgreSQLContainer database, String password, int version) {
        Flyway.configure().dataSource(database.getJdbcUrl(), "nexa_migrator", password)
                .locations("classpath:db/migration").target(Integer.toString(version)).load().migrate();
    }

    private static void applyPreparation(PostgreSQLContainer database, Path preparation) {
        try (Connection connection = adminConnection(database); Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(preparation));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not apply disposable migration preparation " + preparation.getFileName(), exception);
        }
    }

    private static Connection adminConnection(PostgreSQLContainer database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    public record MigrationCredentials(String migratorPassword, String workspaceScopeReaderPassword,
                                       String businessDocumentsScopeReaderPassword,
                                       String internalOperatorPassword) { }
}
