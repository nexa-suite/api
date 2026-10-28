package com.nexa.api.bootstrap.runtime;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Runs central Flyway migrations outside the API process with operator pre-grants between versions. */
public final class CentralDatabaseMigrationBootstrap {
    private static final int LAST_ADMIN_MIGRATION = 168;
    private static final String MIGRATOR = "nexa_migrator";

    public static void main(String[] args) {
        try {
            fromEnvironment(System.getenv()).migrateLatest();
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
            for (String secret : List.of(
                    System.getenv().getOrDefault("NEXA_DATABASE_ADMIN_PASSWORD", ""),
                    System.getenv().getOrDefault("NEXA_DATABASE_RUNTIME_PASSWORD", ""),
                    System.getenv().getOrDefault("NEXA_DATABASE_MIGRATOR_PASSWORD", ""),
                    System.getenv().getOrDefault("NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD", ""),
                    System.getenv().getOrDefault("NEXA_WORKSPACE_SCOPE_READER_PASSWORD", ""),
                    System.getenv().getOrDefault("NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD", ""),
                    System.getenv().getOrDefault("NEXA_INTERNAL_OPERATOR_DATABASE_PASSWORD", ""))) {
                if (!secret.isEmpty()) message = message.replace(secret, "[REDACTED]");
            }
            System.err.printf("Central database migration bootstrap failed: %s: %s%n",
                    exception.getClass().getSimpleName(), message);
            System.exit(1);
        }
    }

    static CentralDatabaseMigrationBootstrap fromEnvironment(Map<String, String> environment) {
        return new CentralDatabaseMigrationBootstrap(
                required(environment, "NEXA_DATABASE_URL"),
                required(environment, "NEXA_DATABASE_ADMIN_USERNAME"),
                required(environment, "NEXA_DATABASE_ADMIN_PASSWORD"),
                required(environment, "NEXA_DATABASE_RUNTIME_PASSWORD"),
                required(environment, "NEXA_DATABASE_MIGRATOR_PASSWORD"),
                required(environment, "NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD"),
                required(environment, "NEXA_WORKSPACE_SCOPE_READER_PASSWORD"),
                required(environment, "NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD"),
                required(environment, "NEXA_INTERNAL_OPERATOR_DATABASE_PASSWORD"),
                Path.of(required(environment, "NEXA_DATABASE_PREPARATION_DIRECTORY")));
    }

    public static void migrateLatest(String jdbcUrl, String adminUsername, String adminPassword,
                                     String runtimePassword, String migratorPassword,
                                     String tenantDatabaseProvisionerPassword,
                                     String workspaceScopeReaderPassword,
                                     String businessDocumentsScopeReaderPassword,
                                     String internalOperatorPassword, Path preparationDirectory) throws Exception {
        new CentralDatabaseMigrationBootstrap(jdbcUrl, adminUsername, adminPassword, runtimePassword,
                migratorPassword, tenantDatabaseProvisionerPassword,
                workspaceScopeReaderPassword, businessDocumentsScopeReaderPassword,
                internalOperatorPassword, preparationDirectory).migrateLatest();
    }

    private final String jdbcUrl;
    private final String adminUsername;
    private final String adminPassword;
    private final String runtimePassword;
    private final String migratorPassword;
    private final String tenantDatabaseProvisionerPassword;
    private final String workspaceScopeReaderPassword;
    private final String businessDocumentsScopeReaderPassword;
    private final String internalOperatorPassword;
    private final Path preparationDirectory;

    CentralDatabaseMigrationBootstrap(String jdbcUrl, String adminUsername, String adminPassword,
                                      String runtimePassword, String migratorPassword,
                                      String tenantDatabaseProvisionerPassword,
                                      String workspaceScopeReaderPassword,
                                      String businessDocumentsScopeReaderPassword,
                                      String internalOperatorPassword, Path preparationDirectory) {
        this.jdbcUrl = jdbcUrl;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.runtimePassword = runtimePassword;
        this.migratorPassword = migratorPassword;
        this.tenantDatabaseProvisionerPassword = tenantDatabaseProvisionerPassword;
        this.workspaceScopeReaderPassword = workspaceScopeReaderPassword;
        this.businessDocumentsScopeReaderPassword = businessDocumentsScopeReaderPassword;
        this.internalOperatorPassword = internalOperatorPassword;
        this.preparationDirectory = preparationDirectory;
        if (adminUsername.equals(MIGRATOR)) {
            throw new IllegalArgumentException("Central database administrator must be separate from nexa_migrator");
        }
        if (adminPassword.equals(migratorPassword)) {
            throw new IllegalArgumentException("Central database administrator and nexa_migrator passwords must differ");
        }
        if (adminPassword.equals(runtimePassword) || migratorPassword.equals(runtimePassword)) {
            throw new IllegalArgumentException("Central database administrator, runtime, and migrator passwords must differ");
        }
        if (tenantDatabaseProvisionerPassword.equals(adminPassword)
                || tenantDatabaseProvisionerPassword.equals(runtimePassword)
                || tenantDatabaseProvisionerPassword.equals(migratorPassword)) {
            throw new IllegalArgumentException("Tenant database provisioner password must be separate from administrator, runtime, and migrator passwords");
        }
    }

    void migrateLatest() throws Exception {
        prepareRestrictedRoles();

        Flyway administratorFlyway = flyway(adminUsername, adminPassword, null);
        MigrationVersion current = currentVersion(administratorFlyway);
        MigrationVersion lastAdminMigration = MigrationVersion.fromVersion(Integer.toString(LAST_ADMIN_MIGRATION));
        if (current == null || current.compareTo(lastAdminMigration) < 0) {
            System.out.printf("Applying central migrations through V%d with administrator connection.%n",
                    LAST_ADMIN_MIGRATION);
            flyway(adminUsername, adminPassword, Integer.toString(LAST_ADMIN_MIGRATION)).migrate();
        }

        int appliedVersion = versionNumber(currentVersion(administratorFlyway));
        for (Path preparation : preparationsAfter(appliedVersion)) {
            int version = preparationVersion(preparation);
            if (version - 1 > appliedVersion) {
                migrateThrough(version - 1);
                appliedVersion = versionNumber(currentVersion(administratorFlyway));
            }
            System.out.printf("Applying central migration preparation %s.%n", preparation.getFileName());
            applyPreparation(preparation);
            migrateThrough(version);
            appliedVersion = versionNumber(currentVersion(administratorFlyway));
        }

        System.out.println("Applying remaining central migrations with direct nexa_migrator connection.");
        flyway(MIGRATOR, migratorPassword, null).migrate();
    }

    private void prepareRestrictedRoles() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            ensureLoginRole(statement, "nexa_migrator", migratorPassword, null);
            ensureLoginRole(statement, "nexa_tenant_database_provisioner",
                    tenantDatabaseProvisionerPassword, null);
            ensureRole(statement, "nexa_workspace_scope_reader", false, false);
            ensureLoginRole(statement, "nexa_workspace_scope_reader_login", workspaceScopeReaderPassword,
                    "nexa_workspace_scope_reader");
            ensureRole(statement, "nexa_internal_operator", false, false);
            ensureLoginRole(statement, "nexa_internal_operator_login", internalOperatorPassword,
                    "nexa_internal_operator");
            ensureMembership(statement, "nexa_workspace_scope_reader", "nexa_workspace_scope_reader_login");
            ensureMembership(statement, "nexa_internal_operator", "nexa_internal_operator_login");
            ensureLoginRole(statement, "nexa_business_documents_scope_reader",
                    businessDocumentsScopeReaderPassword, null);
            ensureLoginRole(statement, "nexa_runtime", runtimePassword, null);
        }
    }

    private static void ensureRole(Statement statement, String name, boolean login, boolean inherit)
            throws SQLException {
        RoleState state = roleState(statement, name);
        if (state == null) {
            statement.execute("CREATE ROLE " + identifier(name) + " " + (login ? "LOGIN" : "NOLOGIN") + " "
                    + (inherit ? "INHERIT" : "NOINHERIT")
                    + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            state = roleState(statement, name);
        }
        if (state == null || state.login() != login || state.inherit() != inherit || state.superuser()
                || state.createDatabase() || state.createRole() || state.replication() || state.bypassRls()) {
            throw new IllegalStateException("Existing database role " + name + " has unsafe role attributes");
        }
    }

    private static void ensureLoginRole(Statement statement, String name, String password,
                                        String expectedMembershipRole) throws SQLException {
        ensureRole(statement, name, true, false);
        if (expectedMembershipRole == null) verifyNoMembership(statement, name);
        if (password != null) {
            statement.execute("ALTER ROLE " + identifier(name) + " PASSWORD " + literal(password));
        }
    }

    private static void ensureMembership(Statement statement, String role, String member) throws SQLException {
        String query = "SELECT member_role.rolname, granted_role.rolname, membership.inherit_option, "
                + "membership.set_option, membership.admin_option "
                + "FROM pg_auth_members membership "
                + "JOIN pg_roles granted_role ON granted_role.oid=membership.roleid "
                + "JOIN pg_roles member_role ON member_role.oid=membership.member "
                + "WHERE granted_role.rolname=" + literal(role) + " OR member_role.rolname=" + literal(member);
        try (ResultSet result = statement.executeQuery(query)) {
            boolean membershipExists = result.next();
            if (membershipExists) {
                if (!result.getString(1).equals(member) || !result.getString(2).equals(role)
                        || result.getBoolean(3) || !result.getBoolean(4) || result.getBoolean(5)
                        || result.next()) {
                    throw new IllegalStateException("Existing database role membership for " + member
                            + " does not match the isolated " + role + " grant");
                }
            } else {
                statement.execute("GRANT " + identifier(role) + " TO " + identifier(member)
                        + " WITH INHERIT FALSE, SET TRUE, ADMIN FALSE");
            }
        }
        verifyMemberships(statement, role, member);
    }

    private static void verifyMemberships(Statement statement, String role, String expectedMember) throws SQLException {
        String query = "SELECT member_role.rolname, granted_role.rolname, membership.inherit_option, "
                + "membership.set_option, membership.admin_option FROM pg_auth_members membership "
                + "JOIN pg_roles granted_role ON granted_role.oid=membership.roleid "
                + "JOIN pg_roles member_role ON member_role.oid=membership.member "
                + "WHERE member_role.rolname=" + literal(role) + " OR granted_role.rolname=" + literal(role);
        try (ResultSet result = statement.executeQuery(query)) {
            int rows = 0;
            while (result.next()) {
                rows++;
                if (!result.getString(1).equals(expectedMember) || !result.getString(2).equals(role)
                        || result.getBoolean(3) || !result.getBoolean(4) || result.getBoolean(5)) {
                    throw new IllegalStateException("Existing database role " + role + " has unexpected memberships");
                }
            }
            if (rows != 1) {
                throw new IllegalStateException("Database role " + role + " must have one isolated login membership");
            }
        }
    }

    private static void verifyNoMembership(Statement statement, String name) throws SQLException {
        String query = "SELECT 1 FROM pg_auth_members membership "
                + "JOIN pg_roles role_row ON role_row.oid=membership.member OR role_row.oid=membership.roleid "
                + "WHERE role_row.rolname=" + literal(name) + " LIMIT 1";
        try (ResultSet result = statement.executeQuery(query)) {
            if (result.next()) throw new IllegalStateException("Existing database role " + name + " has memberships");
        }
    }

    private static RoleState roleState(Statement statement, String name) throws SQLException {
        String query = "SELECT rolcanlogin, rolinherit, rolsuper, rolcreatedb, rolcreaterole, rolreplication, "
                + "rolbypassrls FROM pg_roles WHERE rolname=" + literal(name);
        try (ResultSet result = statement.executeQuery(query)) {
            if (!result.next()) return null;
            return new RoleState(result.getBoolean(1), result.getBoolean(2), result.getBoolean(3),
                    result.getBoolean(4), result.getBoolean(5), result.getBoolean(6), result.getBoolean(7));
        }
    }

    private void migrateThrough(int version) {
        flyway(MIGRATOR, migratorPassword, Integer.toString(version)).migrate();
    }

    private void applyPreparation(Path preparation) throws Exception {
        String sql = Files.readString(preparation);
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<Path> preparationsAfter(int appliedVersion) throws Exception {
        if (!Files.isDirectory(preparationDirectory) || Files.isSymbolicLink(preparationDirectory)) {
            throw new IllegalStateException("Central migration preparation directory is unavailable or unsafe");
        }
        try (var scripts = Files.list(preparationDirectory)) {
            return scripts.filter(path -> path.getFileName().toString()
                            .matches("prepare-v[0-9]+-.+\\.sql"))
                    .filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path))
                    .filter(path -> preparationVersion(path) > Math.max(LAST_ADMIN_MIGRATION, appliedVersion))
                    .sorted(Comparator.comparingInt(CentralDatabaseMigrationBootstrap::preparationVersion))
                    .toList();
        }
    }

    private static int preparationVersion(Path preparation) {
        String name = preparation.getFileName().toString();
        int separator = name.indexOf('-', "prepare-v".length());
        return Integer.parseInt(name.substring("prepare-v".length(), separator));
    }

    private static MigrationVersion currentVersion(Flyway flyway) {
        MigrationInfo current = flyway.info().current();
        return current == null ? null : current.getVersion();
    }

    private static int versionNumber(MigrationVersion version) {
        return version == null ? 0 : Integer.parseInt(version.getVersion());
    }

    private Flyway flyway(String username, String password, String target) {
        var configuration = Flyway.configure().dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, adminUsername, adminPassword);
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String identifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private record RoleState(boolean login, boolean inherit, boolean superuser, boolean createDatabase,
                             boolean createRole, boolean replication, boolean bypassRls) { }
}
