package com.nexa.api.shared.infrastructure.security;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nexa.api.support.PostgresIntegrationSupport.migratorDatabasePassword;
import static com.nexa.api.support.PostgresIntegrationSupport.migratorDatabaseUsername;
import static com.nexa.api.support.PostgresIntegrationSupport.openMigratorConnection;
import static com.nexa.api.support.PostgresIntegrationSupport.openRuntimeConnection;
import static com.nexa.api.support.PostgresIntegrationSupport.runtimeDatabasePassword;
import static com.nexa.api.support.PostgresIntegrationSupport.runtimeDatabaseUsername;
import static com.nexa.api.support.PostgresIntegrationSupport.runtimeJdbcUrl;

/** Verifies RLS with the real least-privilege runtime login, without role switching. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class RlsRuntimeDatabaseIsolationIT {

    static {
        if (Boolean.getBoolean("nexa.integration.enabled")) {
            Flyway.configure()
                    .dataSource(runtimeJdbcUrl(), migratorDatabaseUsername(), migratorDatabasePassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        }
    }

    @Test
    void runtimeLoginIsScopedAcrossTenantsAndRlsTablesAndFailsClosedWithoutScope() throws Exception {
        Fixture fixture = insertFixture();
        try {
            try (Connection connection = openRuntimeConnection()) {
                assertRuntimeIdentityAndPrivileges(connection);

                for (ScopedRow row : fixture.rows()) {
                    setSessionScope(connection, row.scope());
                    assertVisibleRows(connection, row, fixture.rows());
                }

                setSessionScope(connection, fixture.rows().get(0).scope());
                ScopedRow foreign = fixture.rows().get(2);
                assertThat(count(connection, "select count(*) from sales.client_account where id = ?", foreign.accountId()))
                        .as("RLS must hide a different tenant even with an explicit id predicate")
                        .isZero();
                assertThat(count(connection, "select count(*) from sales.client_account_address where id = ?", foreign.addressId()))
                        .as("RLS must hide a different tenant in the second protected table")
                        .isZero();

                clearSessionScope(connection);
                assertThat(currentSetting(connection, "app.current_tenant_id")).as("tenant scope after RESET").isIn(null, "");
                assertThat(currentSetting(connection, "app.current_workspace_id")).as("workspace scope after RESET").isIn(null, "");
                assertThat(count(connection, "select count(*) from sales.client_account")).as("missing tenant scope must return no rows").isZero();
                assertThat(count(connection, "select count(*) from sales.client_account_address")).as("missing workspace scope must return no rows").isZero();
            }
        } finally {
            deleteFixture(fixture);
            RlsRequestScope.clear();
        }
    }

    @Test
    void pooledRuntimeConnectionClearsScopeBeforeTheSameConnectionIsReused() throws Exception {
        Fixture fixture = insertFixture();
        RlsRequestScope.clear();
        try (HikariDataSource pool = runtimePool()) {
            JdbcTemplate scopedJdbc = new JdbcTemplate(new RlsScopedDataSource(pool));

            RlsRequestScope.set(fixture.rows().get(0).scope().tenantId(), fixture.rows().get(0).scope().workspaceId());
            int firstBackendPid = scopedJdbc.queryForObject("select pg_backend_pid()", Integer.class);
            assertThat(scopedJdbc.queryForObject("select current_user", String.class))
                    .as("the pooled connection must authenticate directly as the runtime user")
                    .isEqualTo(runtimeDatabaseUsername());
            assertThat(scopedJdbc.queryForObject("select count(*) from sales.client_account", Integer.class))
                    .as("first scoped checkout").isEqualTo(1);

            RlsRequestScope.clear();
            assertThat(scopedJdbc.queryForObject("select pg_backend_pid()", Integer.class))
                    .as("maximumPoolSize=1 must return the same physical connection")
                    .isEqualTo(firstBackendPid);
            assertThat(scopedJdbc.queryForObject("select current_setting('app.current_tenant_id', true)", String.class))
                    .as("scope must be reset before the connection returns to the pool")
                    .isEmpty();
            assertThat(scopedJdbc.queryForObject("select count(*) from sales.client_account", Integer.class))
                    .as("a reused connection without a request scope must fail closed")
                    .isEqualTo(0);

            ScopedRow second = fixture.rows().get(1);
            RlsRequestScope.set(second.scope().tenantId(), second.scope().workspaceId());
            assertThat(scopedJdbc.queryForObject("select count(*) from sales.client_account", Integer.class))
                    .as("the next request must receive only its own workspace")
                    .isEqualTo(1);
            assertThat(scopedJdbc.queryForObject("select count(*) from sales.client_account_address", Integer.class))
                    .as("the next request must receive only its own address rows")
                    .isEqualTo(1);
        } finally {
            RlsRequestScope.clear();
            deleteFixture(fixture);
        }
    }

    private static HikariDataSource runtimePool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(runtimeJdbcUrl());
        config.setUsername(runtimeDatabaseUsername());
        config.setPassword(runtimeDatabasePassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    private static void assertRuntimeIdentityAndPrivileges(Connection connection) throws SQLException {
        assertThat(scalar(connection, "select current_user")).as("current_user").isEqualTo(runtimeDatabaseUsername());
        assertThat(scalar(connection, "select session_user")).as("session_user").isEqualTo(runtimeDatabaseUsername());

        try (PreparedStatement statement = connection.prepareStatement("""
                select rolcanlogin, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole,
                       has_schema_privilege(current_user, 'sales', 'USAGE'),
                       has_schema_privilege(current_user, 'sales', 'CREATE'),
                       has_table_privilege(current_user, 'sales.client_account', 'SELECT'),
                       has_table_privilege(current_user, 'sales.client_account', 'TRUNCATE')
                from pg_roles
                where rolname = current_user
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("runtime role must exist").isTrue();
                assertThat(result.getBoolean(1)).as("runtime role must be able to log in").isTrue();
                assertThat(result.getBoolean(2)).as("runtime role must not be superuser").isFalse();
                assertThat(result.getBoolean(3)).as("runtime role must not bypass RLS").isFalse();
                assertThat(result.getBoolean(4)).as("runtime role must not create databases").isFalse();
                assertThat(result.getBoolean(5)).as("runtime role must not create roles").isFalse();
                assertThat(result.getBoolean(6)).as("runtime role needs only schema usage").isTrue();
                assertThat(result.getBoolean(7)).as("runtime role must not create objects in the schema").isFalse();
                assertThat(result.getBoolean(8)).as("runtime role needs table reads").isTrue();
                assertThat(result.getBoolean(9)).as("runtime role must not truncate tenant data").isFalse();
                assertThat(result.next()).isFalse();
            }
        }

        List<String> forceRlsTables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'sales'
                   and c.relname in ('client_account', 'client_account_address', 'client_account_membership',
                       'manual_sales_order_draft', 'manual_sales_order_draft_line', 'manual_sales_order_draft_idempotency')
                   and c.relrowsecurity
                   and c.relforcerowsecurity
                 order by c.relname
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) forceRlsTables.add(result.getString(1));
            }
        }
        assertThat(forceRlsTables)
                .as("RLS must be enabled and forced for every table used by this isolation proof")
                .containsExactly("client_account", "client_account_address", "client_account_membership",
                        "manual_sales_order_draft", "manual_sales_order_draft_idempotency", "manual_sales_order_draft_line");
    }

    private static void assertVisibleRows(Connection connection, ScopedRow expected, List<ScopedRow> allRows) throws SQLException {
        assertThat(count(connection, "select count(*) from sales.client_account"))
                .as("client accounts visible for tenant %s/workspace %s", expected.scope().tenantId(), expected.scope().workspaceId())
                .isEqualTo(1);
        assertThat(count(connection, "select count(*) from sales.client_account_address"))
                .as("addresses visible for tenant %s/workspace %s", expected.scope().tenantId(), expected.scope().workspaceId())
                .isEqualTo(1);
        assertThat(count(connection, "select count(*) from sales.client_account where tenant_id = ? and workspace_id = ?",
                expected.scope().tenantId(), expected.scope().workspaceId())).isEqualTo(1);
        for (ScopedRow foreign : allRows) {
            if (foreign == expected) continue;
            assertThat(count(connection, "select count(*) from sales.client_account where id = ?", foreign.accountId()))
                    .as("foreign account must be invisible").isZero();
            assertThat(count(connection, "select count(*) from sales.client_account_address where id = ?", foreign.addressId()))
                    .as("foreign address must be invisible").isZero();
        }
    }

    private static Fixture insertFixture() throws SQLException {
        UUID tenantOne = UUID.randomUUID();
        UUID tenantTwo = UUID.randomUUID();
        List<ScopedRow> rows = new ArrayList<>();
        rows.add(insertRow(tenantOne, UUID.randomUUID(), "one", true));
        rows.add(insertRow(tenantOne, UUID.randomUUID(), "two", false));
        rows.add(insertRow(tenantTwo, UUID.randomUUID(), "three", true));
        return new Fixture(rows, List.of(tenantOne, tenantTwo));
    }

    private static ScopedRow insertRow(UUID tenantId, UUID workspaceId, String label, boolean createTenant) throws SQLException {
        UUID accountId = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, new Scope(tenantId, workspaceId));
                if (createTenant) {
                    execute(connection, "insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                            tenantId, "RLS V1 tenant " + label, "rls-v1-tenant-" + tenantId, now, now);
                }
                execute(connection, "insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                        workspaceId, tenantId, "RLS V1 workspace " + label, "rls-v1-workspace-" + workspaceId, now, now);
                execute(connection, "insert into sales.client_account(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        accountId, tenantId, workspaceId, "RLSV1-" + label + "-" + accountId.toString().substring(0, 8),
                        "RLS V1 business " + label, "RLS V1 commercial " + label, "PE", "RUC", "RLSV1" + accountId.toString().replace("-", "").substring(0, 10),
                        "STANDARD", "RLS V1 Test", "rls-v1-" + label + "@example.test", "+51000000000", "STANDARD", "CREDIT", "ACTIVE", now, now);
                execute(connection, "insert into sales.client_account_address(id,tenant_id,workspace_id,client_account_id,label,recipient_name,address_line,source,default_address,status,created_at,updated_at) values (?,?,?,?,?,?,?,'MANUAL',true,'ACTIVE',?,?)",
                        addressId, tenantId, workspaceId, accountId, "RLS V1 address " + label, "RLS V1 recipient", "RLS V1 address line", now, now);
                connection.commit();
                return new ScopedRow(new Scope(tenantId, workspaceId), accountId, addressId);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void deleteFixture(Fixture fixture) throws SQLException {
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                for (ScopedRow row : fixture.rows()) {
                    setSessionScope(connection, row.scope());
                    execute(connection, "delete from sales.client_account_address where id = ?", row.addressId());
                    execute(connection, "delete from sales.client_account where id = ?", row.accountId());
                    execute(connection, "delete from tenant_management.workspace where id = ?", row.scope().workspaceId());
                }
                for (UUID tenantId : fixture.tenantIds()) execute(connection, "delete from tenant_management.tenant where id = ?", tenantId);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void setSessionScope(Connection connection, Scope scope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config('app.current_tenant_id', ?, false), set_config('app.current_workspace_id', ?, false)")) {
            statement.setString(1, scope.tenantId().toString());
            statement.setString(2, scope.workspaceId().toString());
            statement.execute();
        }
    }

    private static void clearSessionScope(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("reset app.current_tenant_id");
            statement.execute("reset app.current_workspace_id");
        }
    }

    private static String currentSetting(Connection connection, String setting) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select current_setting(?, true)")) {
            statement.setString(1, setting);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static int count(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            statement.executeUpdate();
        }
    }

    private record Scope(UUID tenantId, UUID workspaceId) { }
    private record ScopedRow(Scope scope, UUID accountId, UUID addressId) { }
    private record Fixture(List<ScopedRow> rows, List<UUID> tenantIds) { }

}
