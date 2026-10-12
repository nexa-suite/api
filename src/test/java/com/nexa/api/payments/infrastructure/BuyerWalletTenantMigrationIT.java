package com.nexa.api.payments.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BuyerWalletTenantMigrationIT {
    private static final String MIGRATOR = "nexa_migrator";
    private static final String MIGRATOR_PASSWORD = "tenant-wallet-migrator-test-only";
    private static final String RUNTIME = "nexa_runtime";
    private static final String RUNTIME_PASSWORD = "tenant-wallet-runtime-test-only";
    private static final String POLICY_WRITER = "nexa_policy_snapshot_writer";
    private static final String POLICY_WRITER_PASSWORD = "tenant-wallet-policy-writer-test-only";
    private static final UUID TENANT_ID = UUID.fromString("4cd87c84-3c1f-46b9-92dc-6205994d1a51");
    private static final UUID WORKSPACE_ID = UUID.fromString("3bc66aef-6b20-4a1c-a52c-9cc5be116815");
    private static final UUID DATABASE_ID = UUID.fromString("2f0fd93a-100d-4fc0-b23c-3a0a8f525d6a");
    private static final UUID BUYER_IDENTITY_ID = UUID.fromString("09e69362-8805-4eb2-b4fe-64ed2320a6b3");

    @Container
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("tenant_wallet")
            .withUsername("postgres")
            .withPassword("tenant-wallet-admin-test-only");

    @Test
    void v5WalletTablesStayTenantLocalAndRuntimeScopeCannotCrossTheAnchor() throws Exception {
        provisionRoles();
        migrateTenant("2");
        seedScope();
        migrateTenant("5");

        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            try (ResultSet version = statement.executeQuery("select version from public.flyway_schema_history "
                    + "where success order by installed_rank desc limit 1")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getString(1)).isEqualTo("5");
            }
            try (ResultSet row = statement.executeQuery("select c.relrowsecurity,c.relforcerowsecurity "
                    + "from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                    + "where n.nspname='payments' and c.relname='buyer_wallet_account'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean(1)).isTrue();
                assertThat(row.getBoolean(2)).isTrue();
            }
            try (ResultSet foreignKeys = statement.executeQuery("select count(*) from pg_constraint c "
                    + "join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace "
                    + "where n.nspname='payments' and t.relname='buyer_wallet_account' and c.contype='f'")) {
                foreignKeys.next();
                assertThat(foreignKeys.getLong(1)).isEqualTo(1);
            }
            try (ResultSet centralAuth = statement.executeQuery("select to_regclass('iam.user_account'), "
                    + "to_regclass('tenant_management.workspace_membership')")) {
                centralAuth.next();
                assertThat(centralAuth.getObject(1)).isNull();
                assertThat(centralAuth.getObject(2)).isNull();
            }
        }

        try (Connection runtime = runtimeConnection()) {
            runtime.setAutoCommit(false);
            setScope(runtime, TENANT_ID, WORKSPACE_ID);
            UUID accountId = UUID.randomUUID();
            try (PreparedStatement insert = runtime.prepareStatement("insert into payments.buyer_wallet_account "
                    + "(id,tenant_id,workspace_id,buyer_identity_id) values (?,?,?,?)")) {
                insert.setObject(1, accountId);
                insert.setObject(2, TENANT_ID);
                insert.setObject(3, WORKSPACE_ID);
                insert.setObject(4, BUYER_IDENTITY_ID);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            assertThat(countWalletAccounts(runtime)).isEqualTo(1);

            setScope(runtime, TENANT_ID, UUID.randomUUID());
            assertThat(countWalletAccounts(runtime)).isZero();
            runtime.rollback();
        }

        try (Connection runtime = runtimeConnection()) {
            runtime.setAutoCommit(false);
            setScope(runtime, TENANT_ID, WORKSPACE_ID);
            try (PreparedStatement insertWithBalance = runtime.prepareStatement("insert into payments.buyer_wallet_account "
                    + "(id,tenant_id,workspace_id,buyer_identity_id,posted_balance) values (?,?,?,?,?)")) {
                insertWithBalance.setObject(1, UUID.randomUUID());
                insertWithBalance.setObject(2, TENANT_ID);
                insertWithBalance.setObject(3, WORKSPACE_ID);
                insertWithBalance.setObject(4, UUID.randomUUID());
                insertWithBalance.setBigDecimal(5, new java.math.BigDecimal("1.0000"));
                assertThatThrownBy(insertWithBalance::executeUpdate).isInstanceOf(SQLException.class);
            }
            runtime.rollback();
        }
    }

    private static void provisionRoles() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + MIGRATOR + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + RUNTIME + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + POLICY_WRITER + " LOGIN PASSWORD '" + POLICY_WRITER_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT CONNECT, CREATE ON DATABASE tenant_wallet TO " + MIGRATOR);
            statement.execute("GRANT CONNECT ON DATABASE tenant_wallet TO " + RUNTIME + ", " + POLICY_WRITER);
            statement.execute("GRANT CREATE ON SCHEMA public TO " + MIGRATOR);
        }
    }

    private static void migrateTenant(String target) {
        Path migrationPath = Path.of("src/main/resources/db/tenant-migration").toAbsolutePath().normalize();
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
                .locations("filesystem:" + migrationPath)
                .target(target)
                .load().migrate();
    }

    private static void seedScope() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD);
             PreparedStatement identity = connection.prepareStatement("insert into nexa_platform.tenant_business_database_identity "
                     + "(singleton,tenant_id,database_identity) values (true,?,?)");
             PreparedStatement anchor = connection.prepareStatement("insert into nexa_platform.tenant_workspace_scope_anchor "
                     + "(tenant_id,workspace_id) values (?,?)")) {
            identity.setObject(1, TENANT_ID);
            identity.setObject(2, DATABASE_ID);
            identity.executeUpdate();
            anchor.setObject(1, TENANT_ID);
            anchor.setObject(2, WORKSPACE_ID);
            anchor.executeUpdate();
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }

    private static Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
    }

    private static void setScope(Connection connection, UUID tenantId, UUID workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config('app.current_tenant_id', ?, true), set_config('app.current_workspace_id', ?, true)")) {
            statement.setString(1, tenantId.toString());
            statement.setString(2, workspaceId.toString());
            statement.execute();
        }
    }

    private static long countWalletAccounts(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("select count(*) from payments.buyer_wallet_account");
             ResultSet result = query.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }
}
