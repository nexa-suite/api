package com.nexa.api.bootstrap.runtime.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves the narrow operational bridge required by V140 on a non-superuser Neon-style database.
 * The bridge is removed in a finally block, including when a later migration fails.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class V140RoleDefinitionOperationalPolicyIT {
    private static final String ADMIN_USERNAME = "nexa_v140_admin";
    private static final String ADMIN_PASSWORD = "test-only-v140-admin-password";
    private static final String MIGRATOR_USERNAME = "nexa_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-v140-migrator-password";
    private static final String RUNTIME_USERNAME = "nexa_runtime";
    private static final String RUNTIME_PASSWORD = "test-only-v140-runtime-password";
    private static final String V140_ID = "007b12ab-81dd-307a-ac41-9bc9888924ed";
    private static final String V140_CODE = "business_operations_manager";
    private static final String V140_NAME = "Business operations manager";
    private static final String V140_DESCRIPTION =
            "Coordinates operational exceptions without implicit stock or cold-chain authority";
    private static final Path PREPARE_SCRIPT = Path.of("ops/database/prepare-v140-role-definition.sql");
    private static final Path CLEANUP_SCRIPT = Path.of("ops/database/cleanup-v140-role-definition.sql");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("nexa")
            .withUsername(ADMIN_USERNAME)
            .withPassword(ADMIN_PASSWORD);

    @Test
    void migratorCanApplyV140OnlyThroughTheTemporaryExactPolicyAndAlwaysCleansItUp() throws Exception {
        createRolesBeforeBaselineMigration();
        migrateAs(ADMIN_USERNAME, ADMIN_PASSWORD, "139");
        transferPendingMigrationOwnership();
        assertThat(schemaHistoryVersion()).isEqualTo("139");

        assertThatThrownBy(() -> insertRoleAsMigrator(V140_ID, V140_CODE))
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));

        Throwable originalFlywayFailure = catchThrowable(() -> migrateAs(MIGRATOR_USERNAME, MIGRATOR_PASSWORD, null));
        assertThat(originalFlywayFailure).isInstanceOf(FlywayException.class);
        assertThat(sqlState(originalFlywayFailure)).isEqualTo("42501");
        assertThat(schemaHistoryVersion()).isEqualTo("139");

        executeScriptAs(MIGRATOR_USERNAME, MIGRATOR_PASSWORD, PREPARE_SCRIPT);
        try {
            assertThatCode(V140RoleDefinitionOperationalPolicyIT::assertExactInsertAllowedAndRolledBack)
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> insertRoleAsMigrator(UUID.randomUUID().toString(), "different_role"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
        } finally {
            executeScriptAs(MIGRATOR_USERNAME, MIGRATOR_PASSWORD, CLEANUP_SCRIPT);
        }
        assertTemporaryPolicyIsAbsentAndForceRlsRemainsEnabled();

        boolean prepared = false;
        try {
            executeScriptAs(MIGRATOR_USERNAME, MIGRATOR_PASSWORD, PREPARE_SCRIPT);
            prepared = true;

            migrateAs(MIGRATOR_USERNAME, MIGRATOR_PASSWORD, null);
            assertThat(schemaHistoryVersion()).isEqualTo("145");
            assertThat(canonicalRoleExists()).isTrue();

            assertRuntimeCannotInsertWithMatchedContext();
            assertThatCode(() -> newRuntimeValidator()).doesNotThrowAnyException();
        } finally {
            if (prepared) {
                executeScriptAs(MIGRATOR_USERNAME, MIGRATOR_PASSWORD, CLEANUP_SCRIPT);
            }
        }

        assertTemporaryPolicyIsAbsentAndForceRlsRemainsEnabled();
        assertThatCode(() -> executeScriptAs(MIGRATOR_USERNAME, MIGRATOR_PASSWORD, CLEANUP_SCRIPT))
                .doesNotThrowAnyException();
        assertTemporaryPolicyIsAbsentAndForceRlsRemainsEnabled();
        assertRuntimeCannotInsertWithMatchedContext();
    }

    private static void createRolesBeforeBaselineMigration() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create role " + MIGRATOR_USERNAME
                    + " login password '" + MIGRATOR_PASSWORD + "' inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
            statement.execute("create role " + RUNTIME_USERNAME
                    + " login password '" + RUNTIME_PASSWORD + "' inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
            statement.execute("grant connect on database nexa to " + MIGRATOR_USERNAME + ", " + RUNTIME_USERNAME);
        }
    }

    private static void transferPendingMigrationOwnership() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("grant usage, create on schema public, tenant_management, logistics, warehouse, sales to " + MIGRATOR_USERNAME);
            statement.execute("alter table public.flyway_schema_history owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table tenant_management.permission_definition owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table tenant_management.role_definition owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table tenant_management.role_permission owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table tenant_management.membership_role_definition owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table tenant_management.membership_authorization_state owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table logistics.temperature_evidence owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table logistics.operational_exception_case owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table logistics.driver_coordinate owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table warehouse.inventory_temperature_evaluation owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table warehouse.inventory_lot_disposition owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table tenant_management.organization_invitation_role owner to " + MIGRATOR_USERNAME);
            statement.execute("alter table tenant_management.organization_registration_draft_idempotency owner to " + MIGRATOR_USERNAME);
            statement.execute("""
                    grant references on logistics.operational_exception_case, tenant_management.workspace_membership,
                        logistics.temperature_evidence, warehouse.inventory_temperature_evaluation to %s
                    """.formatted(MIGRATOR_USERNAME));
            statement.execute("grant execute on function sales.prevent_append_only_mutation() to " + MIGRATOR_USERNAME);
            statement.execute("grant execute on function tenant_management.bump_authorization_memberships(uuid) to " + MIGRATOR_USERNAME);
            statement.execute("grant execute on function tenant_management.bump_role_permission_authorization() to " + MIGRATOR_USERNAME);
        }
    }

    private static void migrateAs(String username, String password, String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), username, password)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static void executeScriptAs(String username, String password, Path script) throws Exception {
        String sql = Files.readString(script);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), username, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void insertRoleAsMigrator(String id, String code) throws SQLException {
        try (Connection connection = migratorConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into tenant_management.role_definition
                         (id,tenant_id,workspace_id,code,name,description,role_type,status,created_by_membership_id,created_at,updated_at,version)
                     values (?,null,null,?,?,?,'SYSTEM_TEMPLATE','ACTIVE',null,current_timestamp,current_timestamp,0)
                     """)) {
            statement.setObject(1, UUID.fromString(id));
            statement.setString(2, code);
            statement.setString(3, V140_NAME);
            statement.setString(4, V140_DESCRIPTION);
            statement.executeUpdate();
        }
    }

    private static void assertExactInsertAllowedAndRolledBack() throws SQLException {
        try (Connection connection = migratorConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into tenant_management.role_definition
                         (id,tenant_id,workspace_id,code,name,description,role_type,status,created_by_membership_id,created_at,updated_at,version)
                     values (?::uuid,null,null,?,?,?,'SYSTEM_TEMPLATE','ACTIVE',null,current_timestamp,current_timestamp,0)
                     """)) {
            connection.setAutoCommit(false);
            statement.setString(1, V140_ID);
            statement.setString(2, V140_CODE);
            statement.setString(3, V140_NAME);
            statement.setString(4, V140_DESCRIPTION);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            connection.rollback();
        }
    }

    private static boolean canonicalRoleExists() throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select count(*)
                       from tenant_management.role_definition
                      where id = ?::uuid
                        and tenant_id is null
                        and workspace_id is null
                        and code = ?
                        and name = ?
                        and description = ?
                        and role_type = 'SYSTEM_TEMPLATE'
                        and status = 'ACTIVE'
                        and created_by_membership_id is null
                        and version = 0
                     """)) {
            statement.setString(1, V140_ID);
            statement.setString(2, V140_CODE);
            statement.setString(3, V140_NAME);
            statement.setString(4, V140_DESCRIPTION);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static void assertRuntimeCannotInsertWithMatchedContext() throws SQLException {
        try (Connection connection = runtimeConnection(); Statement statement = connection.createStatement()) {
            statement.execute("select set_config('app.current_tenant_id', '11111111-1111-1111-1111-111111111111', false)");
            statement.execute("select set_config('app.current_workspace_id', '22222222-2222-2222-2222-222222222222', false)");
            assertThatThrownBy(() -> statement.execute("""
                    insert into tenant_management.role_definition
                        (id,tenant_id,workspace_id,code,name,description,role_type,status,created_by_membership_id,created_at,updated_at,version)
                    values ('007b12ab-81dd-307a-ac41-9bc9888924ed',null,null,'business_operations_manager',
                            'Business operations manager',
                            'Coordinates operational exceptions without implicit stock or cold-chain authority',
                            'SYSTEM_TEMPLATE','ACTIVE',null,current_timestamp,current_timestamp,0)
                    """))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
        }
    }

    private static void assertTemporaryPolicyIsAbsentAndForceRlsRemainsEnabled() throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select c.relrowsecurity,
                            c.relforcerowsecurity,
                            exists (
                                select 1
                                  from pg_policy p
                                 where p.polrelid = c.oid
                                   and p.polname = 'nexa_v140_migrator_insert'
                            )
                       from pg_class c
                       join pg_namespace n on n.oid = c.relnamespace
                      where n.nspname = 'tenant_management'
                        and c.relname = 'role_definition'
                     """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
                assertThat(result.getBoolean(2)).isTrue();
                assertThat(result.getBoolean(3)).isFalse();
            }
        }
    }

    private static void newRuntimeValidator() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), RUNTIME_USERNAME, RUNTIME_PASSWORD);
        new DatabaseRuntimeConfigurationValidator(new JdbcTemplate(dataSource),
                new MockEnvironment().withProperty("NEXA_DATABASE_RUNTIME_USERNAME", RUNTIME_USERNAME));
    }

    private static String schemaHistoryVersion() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select version from public.flyway_schema_history where success order by installed_rank desc limit 1")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    private static Connection migratorConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
    }

    private static Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USERNAME, RUNTIME_PASSWORD);
    }
}
