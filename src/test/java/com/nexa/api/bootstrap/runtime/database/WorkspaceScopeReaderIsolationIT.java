package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc.JdbcInvitationPersistenceAdapter;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.JdbcWorkspaceScopeReader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WorkspaceScopeReaderIsolationIT {
    private static final String RUNTIME_PASSWORD = "test-only-runtime-password";
    private static final String MIGRATOR_PASSWORD = "test-only-migrator-password";
    private static final String READER_PASSWORD = "test-only-workspace-reader-password";
    private static final UUID ACTIVE_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INACTIVE_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SUSPENDED_WORKSPACE_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ACTIVE_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID INACTIVE_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID SUSPENDED_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID ACTIVE_MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID ACTIVE_USER = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private static final UUID ACTIVE_INVITATION = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID INACTIVE_INVITATION = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final UUID SUSPENDED_INVITATION = UUID.fromString("00000000-0000-0000-0000-000000000023");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("nexa")
            .withUsername("nexa")
            .withPassword("test-only-admin-password");

    @BeforeAll
    static void prepareIsolatedDatabase() throws Exception {
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION btree_gist");
            statement.execute("CREATE ROLE nexa_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE nexa_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'");
            statement.execute("CREATE ROLE nexa_workspace_scope_reader NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_workspace_scope_reader_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + READER_PASSWORD + "'");
            statement.execute("GRANT nexa_workspace_scope_reader TO nexa_workspace_scope_reader_login WITH INHERIT FALSE, SET TRUE");
            statement.execute("CREATE SCHEMA tenant_management");
            statement.execute("CREATE SCHEMA sales");
            statement.execute("CREATE TABLE public.flyway_schema_history (installed_rank integer primary key, version varchar(50), success boolean not null)");
            statement.execute("INSERT INTO public.flyway_schema_history(installed_rank, version, success) VALUES (1, '174', true)");
            statement.execute("CREATE TABLE tenant_management.tenant (id uuid primary key, slug text not null, status text not null)");
            statement.execute("CREATE TABLE tenant_management.workspace (tenant_id uuid not null references tenant_management.tenant(id), id uuid not null, slug text not null, status text not null, primary key (tenant_id, id))");
            statement.execute("CREATE TABLE tenant_management.workspace_membership (id uuid primary key, tenant_id uuid not null, workspace_id uuid not null, user_id uuid not null, membership_type text not null default 'INTERNAL', status text not null default 'ACTIVE')");
            statement.execute("CREATE TABLE tenant_management.tenant_business_database_binding (tenant_id uuid primary key, lifecycle_state text not null)");
            statement.execute("CREATE TABLE tenant_management.tenant_business_database_workspace_anchor_task (tenant_id uuid not null, workspace_id uuid not null, status text not null)");
            statement.execute("CREATE TABLE sales.client_account (id uuid primary key)");
            statement.execute("CREATE TABLE tenant_management.organization_invitation (id uuid primary key, tenant_id uuid not null, workspace_id uuid not null, status text not null, expires_at timestamptz not null, updated_at timestamptz not null, version bigint not null)");
            statement.execute("ALTER TABLE tenant_management.tenant ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE tenant_management.tenant FORCE ROW LEVEL SECURITY");
            statement.execute("CREATE POLICY v102_tenant_root_scope ON tenant_management.tenant USING (id::text = current_setting('app.current_tenant_id', true)) WITH CHECK (id::text = current_setting('app.current_tenant_id', true))");
            statement.execute("CREATE POLICY v102_tenant_root_bootstrap_read ON tenant_management.tenant FOR SELECT USING (lower(slug) = current_setting('app.bootstrap_tenant_slug', true))");
            statement.execute("CREATE POLICY v102_tenant_root_visible_workspace_read ON tenant_management.tenant FOR SELECT USING (EXISTS (SELECT 1 FROM tenant_management.workspace w WHERE w.tenant_id = tenant_management.tenant.id))");
            statement.execute("ALTER TABLE tenant_management.workspace ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE tenant_management.workspace FORCE ROW LEVEL SECURITY");
            statement.execute("CREATE POLICY v102_workspace_tenant_scope ON tenant_management.workspace USING (tenant_id::text = current_setting('app.current_tenant_id', true)) WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true))");
            statement.execute("CREATE POLICY v102_workspace_cross_scope_scan ON tenant_management.workspace FOR SELECT USING (current_setting('app.cross_scope_workspace_scan', true) = 'true')");
            statement.execute("CREATE POLICY v102_workspace_membership_revalidation ON tenant_management.workspace FOR SELECT USING (EXISTS (SELECT 1 FROM tenant_management.workspace_membership m WHERE m.workspace_id = tenant_management.workspace.id AND m.id::text = current_setting('app.workspace_membership_lookup_id', true) AND m.user_id::text = current_setting('app.workspace_membership_lookup_user_id', true)))");
            statement.execute("CREATE POLICY v102_workspace_access_context_user ON tenant_management.workspace FOR SELECT USING (EXISTS (SELECT 1 FROM tenant_management.workspace_membership m WHERE m.workspace_id = tenant_management.workspace.id AND m.user_id::text = current_setting('app.access_context_user_id', true)))");
            statement.execute("CREATE POLICY business_documents_scope_reader_ready_workspace ON tenant_management.workspace AS RESTRICTIVE FOR SELECT USING (current_user <> 'nexa_business_documents_scope_reader' OR (current_setting('app.cross_scope_workspace_scan', true) = 'true' AND status = 'ACTIVE' AND EXISTS (SELECT 1 FROM tenant_management.tenant_business_database_binding binding JOIN tenant_management.tenant_business_database_workspace_anchor_task anchor ON anchor.tenant_id = binding.tenant_id WHERE binding.tenant_id = workspace.tenant_id AND anchor.workspace_id = workspace.id AND binding.lifecycle_state = 'READY' AND anchor.status = 'READY')))");
            statement.execute("ALTER TABLE tenant_management.workspace_membership ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE tenant_management.workspace_membership FORCE ROW LEVEL SECURITY");
            statement.execute("CREATE POLICY v173_workspace_membership_current_scope ON tenant_management.workspace_membership FOR ALL USING (workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')) WITH CHECK (workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))");
            statement.execute("CREATE POLICY v173_workspace_membership_identity_read ON tenant_management.workspace_membership FOR SELECT USING (user_id::text = nullif(current_setting('app.access_context_user_id', true), '') OR (id::text = nullif(current_setting('app.workspace_membership_lookup_id', true), '') AND user_id::text = nullif(current_setting('app.workspace_membership_lookup_user_id', true), '')))");
            statement.execute("CREATE POLICY v174_workspace_membership_tenant_workspace_scope ON tenant_management.workspace_membership FOR ALL USING (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '') AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')) WITH CHECK (tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '') AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), ''))");
            statement.execute("GRANT USAGE ON SCHEMA tenant_management TO nexa_runtime");
            statement.execute("GRANT SELECT ON tenant_management.workspace TO nexa_runtime");
            statement.execute("GRANT SELECT ON tenant_management.workspace_membership TO nexa_runtime");
            statement.execute("GRANT SELECT, UPDATE ON tenant_management.organization_invitation TO nexa_runtime");
            statement.execute("CREATE FUNCTION tenant_management.bump_authorization_membership(uuid) RETURNS void LANGUAGE plpgsql AS 'BEGIN RETURN; END'");
            statement.execute("CREATE FUNCTION tenant_management.bump_authorization_memberships(uuid) RETURNS void LANGUAGE plpgsql AS 'BEGIN RETURN; END'");
            statement.execute("CREATE FUNCTION tenant_management.bump_membership_role_authorization() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
            statement.execute("CREATE FUNCTION tenant_management.bump_role_permission_authorization() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
            statement.execute("ALTER TABLE tenant_management.organization_invitation ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE tenant_management.organization_invitation FORCE ROW LEVEL SECURITY");
            statement.execute("CREATE POLICY v175_test_invitation_scope ON tenant_management.organization_invitation USING (tenant_id::text = current_setting('app.current_tenant_id', true) AND workspace_id::text = current_setting('app.current_workspace_id', true)) WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true) AND workspace_id::text = current_setting('app.current_workspace_id', true))");
            statement.execute("GRANT USAGE ON SCHEMA sales TO nexa_migrator");

            statement.execute("INSERT INTO tenant_management.tenant(id, slug, status) VALUES ('" + ACTIVE_TENANT + "', 'active', 'ACTIVE'), ('" + INACTIVE_TENANT + "', 'inactive', 'SUSPENDED'), ('" + SUSPENDED_WORKSPACE_TENANT + "', 'partial', 'ACTIVE')");
            statement.execute("INSERT INTO tenant_management.workspace(tenant_id, id, slug, status) VALUES ('" + ACTIVE_TENANT + "', '" + ACTIVE_WORKSPACE + "', 'active', 'ACTIVE'), ('" + INACTIVE_TENANT + "', '" + INACTIVE_WORKSPACE + "', 'inactive', 'ACTIVE'), ('" + SUSPENDED_WORKSPACE_TENANT + "', '" + SUSPENDED_WORKSPACE + "', 'suspended', 'SUSPENDED')");
            statement.execute("INSERT INTO tenant_management.workspace_membership(id, tenant_id, workspace_id, user_id) VALUES ('" + ACTIVE_MEMBERSHIP + "', '" + ACTIVE_TENANT + "', '" + ACTIVE_WORKSPACE + "', '" + ACTIVE_USER + "')");
        }

        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT admin_option FROM pg_auth_members WHERE roleid='nexa_workspace_scope_reader'::regrole AND member='nexa_workspace_scope_reader_login'::regrole")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).isFalse();
        }

        Path readerPreparation = Path.of("ops/database/prepare-v175-workspace-scope-reader.sql");
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION tenant_management.v175_reader_security_definer_probe() RETURNS integer LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'");
        }
        assertThatThrownBy(() -> runAdminScript(readerPreparation))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("must not execute accessible application functions except btree_gist operator support");
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("DROP FUNCTION tenant_management.v175_reader_security_definer_probe()");
            statement.execute("CREATE FUNCTION tenant_management.v175_reader_invoker_probe() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
        }
        assertThatThrownBy(() -> runAdminScript(readerPreparation))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("must not execute accessible application functions except btree_gist operator support");
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("DROP FUNCTION tenant_management.v175_reader_invoker_probe()");
        }
        runAdminScript(readerPreparation);
        Path migration = Path.of("src/main/resources/db/migration/V175__restrict_global_workspace_scans_to_scope_reader.sql");
        for (String readerRole : List.of("nexa_workspace_scope_reader", "nexa_workspace_scope_reader_login")) {
            try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
                statement.execute("GRANT SELECT (id) ON sales.client_account TO " + readerRole);
            }
            try {
                assertThatThrownBy(() -> runMigrationAsMigrator(migration))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("must not access other application tables");
            } finally {
                try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
                    statement.execute("REVOKE SELECT (id) ON sales.client_account FROM " + readerRole);
                }
            }
        }
        runMigrationAsMigrator(migration);
    }

    @Test
    void runtimeCannotEnumerateWorkspacesBySettingTheScanGuc() throws Exception {
        try (Connection connection = connect("nexa_runtime", RUNTIME_PASSWORD);
                Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT has_function_privilege(current_user, 'tenant_management.bump_authorization_membership(uuid)'::regprocedure, 'EXECUTE')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).as("runtime keeps its explicit authorization version helper permission").isTrue();
            }
            statement.execute("SELECT set_config('app.cross_scope_workspace_scan', 'true', false)");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM tenant_management.workspace")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }

            statement.execute("SELECT set_config('app.current_tenant_id', '" + ACTIVE_TENANT + "', false)");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM tenant_management.workspace WHERE tenant_id='" + ACTIVE_TENANT + "' AND id='" + ACTIVE_WORKSPACE + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void dedicatedLoginMustSetItsOnlyPrivilegeRoleAndCannotReadOrWriteBusinessData() throws Exception {
        try (Connection connection = connect("nexa_workspace_scope_reader_login", READER_PASSWORD);
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM tenant_management.workspace"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }

        try (Connection connection = connect("nexa_workspace_scope_reader_login", READER_PASSWORD)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
                statement.execute("SET LOCAL ROLE nexa_workspace_scope_reader");
                statement.execute("SET LOCAL app.cross_scope_workspace_scan = 'true'");
                statement.execute("SET LOCAL app.current_tenant_id = '" + ACTIVE_TENANT + "'");
                statement.execute("SET LOCAL app.current_workspace_id = '" + ACTIVE_WORKSPACE + "'");
                statement.execute("SET LOCAL app.access_context_user_id = '" + ACTIVE_USER + "'");
                statement.execute("SET LOCAL app.workspace_membership_lookup_id = '" + ACTIVE_MEMBERSHIP + "'");
                statement.execute("SET LOCAL app.workspace_membership_lookup_user_id = '" + ACTIVE_USER + "'");
                try (ResultSet result = statement.executeQuery("SELECT session_user, current_user, tenant_id, id, status FROM tenant_management.workspace ORDER BY tenant_id, id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("nexa_workspace_scope_reader_login");
                    assertThat(result.getString(2)).isEqualTo("nexa_workspace_scope_reader");
                    assertThat(result.getString(3)).isEqualTo(ACTIVE_TENANT.toString());
                }
                try (ResultSet result = statement.executeQuery("SELECT id, status FROM tenant_management.tenant ORDER BY id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(ACTIVE_TENANT.toString());
                }
                assertPermissionDenied(connection, statement, "SELECT slug FROM tenant_management.workspace");
                assertPermissionDenied(connection, statement, "SELECT slug FROM tenant_management.tenant");
                try (ResultSet result = statement.executeQuery(
                        "SELECT tenant_id, id, workspace_id, user_id FROM tenant_management.workspace_membership")) {
                    assertThat(result.next()).as("forged identity and scope GUCs never expose memberships").isFalse();
                }
                assertPermissionDenied(connection, statement,
                        "SELECT membership_type FROM tenant_management.workspace_membership");
                assertPermissionDenied(connection, statement,
                        "SELECT tenant_management.bump_authorization_membership('00000000-0000-0000-0000-000000000001')");
                assertPermissionDenied(connection, statement, "SELECT * FROM sales.client_account");
                assertWriteRejected(connection, statement,
                        "UPDATE tenant_management.workspace SET status='SUSPENDED'");
                assertWriteRejected(connection, statement,
                        "INSERT INTO tenant_management.workspace(tenant_id, id, slug, status) VALUES ('"
                                + ACTIVE_TENANT + "', '00000000-0000-0000-0000-000000000099', 'extra', 'ACTIVE')");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void scopedReaderAdapterReturnsBoundedMetadataAndActiveScopes() {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(POSTGRES.getJdbcUrl());
        configuration.setUsername("nexa_workspace_scope_reader_login");
        configuration.setPassword(READER_PASSWORD);
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        configuration.setReadOnly(true);
        HikariDataSource pool = new HikariDataSource(configuration);
        try (pool) {
            JdbcWorkspaceScopeReader reader = scopedWorkspaceScopeReader(pool);
            assertThatThrownBy(() -> reader.scanAfter(null, null, 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Explicit worker workspace enumeration scope");
            RlsRequestScope.enableCrossScopeWorkspaceScan();
            List<WorkspaceDirectory.Scope> scopes = reader.scanAfter(null, null, 100);
            assertThat(scopes).extracting(WorkspaceDirectory.Scope::tenantId)
                    .containsExactly(ACTIVE_TENANT, INACTIVE_TENANT, SUSPENDED_WORKSPACE_TENANT);
            List<WorkspaceDirectory.Scope> activeWorkspaces = reader.scanActiveWorkspacesAfter(null, null, 100);
            assertThat(activeWorkspaces).extracting(WorkspaceDirectory.Scope::tenantId)
                    .containsExactly(ACTIVE_TENANT, INACTIVE_TENANT);
            assertThat(activeWorkspaces).extracting(WorkspaceDirectory.Scope::workspaceId)
                    .containsExactly(ACTIVE_WORKSPACE, INACTIVE_WORKSPACE);
            List<WorkspaceDirectory.Scope> active = reader.scanActiveAfter(null, null, 100);
            assertThat(active).extracting(WorkspaceDirectory.Scope::tenantId)
                    .containsExactly(ACTIVE_TENANT);
            assertThat(active).extracting(WorkspaceDirectory.Scope::workspaceId)
                    .containsExactly(ACTIVE_WORKSPACE);
        } finally {
            RlsRequestScope.clearCrossScopeWorkspaceScan();
        }
    }

    @Test
    void scopedReaderFailsClosedWhenAnAccessibleSecurityDefinerFunctionAppears() throws Exception {
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION tenant_management.v175_reader_security_definer_probe() RETURNS integer LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'");
        }

        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(POSTGRES.getJdbcUrl());
        configuration.setUsername("nexa_workspace_scope_reader_login");
        configuration.setPassword(READER_PASSWORD);
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        configuration.setReadOnly(true);
        HikariDataSource pool = new HikariDataSource(configuration);
        try (pool) {
            JdbcWorkspaceScopeReader reader = scopedWorkspaceScopeReader(pool);
            RlsRequestScope.enableCrossScopeWorkspaceScan();
            assertThatThrownBy(() -> reader.scanAfter(null, null, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not execute application functions except approved btree_gist operator support");
        } finally {
            RlsRequestScope.clearCrossScopeWorkspaceScan();
            try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
                statement.execute("DROP FUNCTION tenant_management.v175_reader_security_definer_probe()");
            }
        }
    }

    @Test
    void dedicatedReadTransactionWorksInsideAnExistingCentralTransaction() {
        HikariConfig centralConfiguration = new HikariConfig();
        centralConfiguration.setJdbcUrl(POSTGRES.getJdbcUrl());
        centralConfiguration.setUsername("nexa_runtime");
        centralConfiguration.setPassword(RUNTIME_PASSWORD);
        centralConfiguration.setMaximumPoolSize(1);
        centralConfiguration.setMinimumIdle(0);
        try (HikariDataSource centralPool = new HikariDataSource(centralConfiguration)) {
            var central = RlsScopedDataSource.forCurrentCentralScope(centralPool);
            JdbcTemplate centralJdbc = new JdbcTemplate(central);
            TransactionTemplate outerTransaction = new TransactionTemplate(new DataSourceTransactionManager(central));
            HikariConfig readerConfiguration = new HikariConfig();
            readerConfiguration.setJdbcUrl(POSTGRES.getJdbcUrl());
            readerConfiguration.setUsername("nexa_workspace_scope_reader_login");
            readerConfiguration.setPassword(READER_PASSWORD);
            readerConfiguration.setMaximumPoolSize(1);
            readerConfiguration.setMinimumIdle(0);
            readerConfiguration.setReadOnly(true);
            try (HikariDataSource readerPool = new HikariDataSource(readerConfiguration)) {
                JdbcWorkspaceScopeReader reader = scopedWorkspaceScopeReader(readerPool);
                RlsRequestScope.enableCrossScopeWorkspaceScan();
                List<WorkspaceDirectory.Scope> scopes = outerTransaction.execute(status -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(centralJdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                    List<WorkspaceDirectory.Scope> result = reader.scanAfter(null, null, 100);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(centralJdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                    return result;
                });
                assertThat(scopes).hasSize(3);
            } finally {
                RlsRequestScope.clearCrossScopeWorkspaceScan();
            }
        }
    }

    @Test
    void invitationExpiryUsesReaderScopesAndKeepsBoundedCrossWorkspaceBehavior() throws Exception {
        Instant now = Instant.now();
        try (Connection connection = POSTGRES.createConnection("");
                var insert = connection.prepareStatement("INSERT INTO tenant_management.organization_invitation(id, tenant_id, workspace_id, status, expires_at, updated_at, version) VALUES (?, ?, ?, 'PENDING', ?, ?, 0)")) {
            addExpiredInvitation(insert, ACTIVE_INVITATION, ACTIVE_TENANT, ACTIVE_WORKSPACE, now);
            addExpiredInvitation(insert, INACTIVE_INVITATION, INACTIVE_TENANT, INACTIVE_WORKSPACE, now);
            addExpiredInvitation(insert, SUSPENDED_INVITATION, SUSPENDED_WORKSPACE_TENANT, SUSPENDED_WORKSPACE, now);
        }

        HikariConfig runtimeConfiguration = new HikariConfig();
        runtimeConfiguration.setJdbcUrl(POSTGRES.getJdbcUrl());
        runtimeConfiguration.setUsername("nexa_runtime");
        runtimeConfiguration.setPassword(RUNTIME_PASSWORD);
        runtimeConfiguration.setMaximumPoolSize(2);
        runtimeConfiguration.setMinimumIdle(0);
        HikariConfig readerConfiguration = new HikariConfig();
        readerConfiguration.setJdbcUrl(POSTGRES.getJdbcUrl());
        readerConfiguration.setUsername("nexa_workspace_scope_reader_login");
        readerConfiguration.setPassword(READER_PASSWORD);
        readerConfiguration.setMaximumPoolSize(1);
        readerConfiguration.setMinimumIdle(0);
        readerConfiguration.setReadOnly(true);

        try (HikariDataSource runtimePool = new HikariDataSource(runtimeConfiguration);
                HikariDataSource readerPool = new HikariDataSource(readerConfiguration)) {
            JdbcWorkspaceScopeReader reader = scopedWorkspaceScopeReader(readerPool);
            DataSource central = RlsScopedDataSource.forCurrentCentralScope(runtimePool);
            var transactionManager = new DataSourceTransactionManager(central);
            var invitations = new JdbcInvitationPersistenceAdapter(new JdbcTemplate(central), transactionManager,
                    reader);
            RlsRequestScope.clear();
            assertThat(invitations.expirePending(now.plusSeconds(1), 2))
                    .as("one expiry pass remains within its batch limit").isEqualTo(2);
            assertThat(invitations.expirePending(now.plusSeconds(1), 2))
                    .as("a subsequent pass reaches the remaining Workspace").isEqualTo(1);
        } finally {
            RlsRequestScope.clear();
        }

        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM tenant_management.organization_invitation WHERE status='EXPIRED'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(3);
        }
    }

    private static JdbcWorkspaceScopeReader scopedWorkspaceScopeReader(DataSource pool) {
        DataSource scoped = RlsScopedDataSource.forCurrentCentralScope(pool);
        return new JdbcWorkspaceScopeReader(() -> scoped);
    }

    private static void addExpiredInvitation(java.sql.PreparedStatement insert, UUID invitationId, UUID tenantId,
            UUID workspaceId, Instant now) throws SQLException {
        insert.setObject(1, invitationId);
        insert.setObject(2, tenantId);
        insert.setObject(3, workspaceId);
        insert.setTimestamp(4, Timestamp.from(now.minusSeconds(60)));
        insert.setTimestamp(5, Timestamp.from(now.minusSeconds(60)));
        insert.executeUpdate();
    }

    private static void runAdminScript(Path path) throws Exception {
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(Files.readString(path));
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void runMigrationAsMigrator(Path path) throws Exception {
        try (Connection connection = connect("nexa_migrator", MIGRATOR_PASSWORD)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(Files.readString(path));
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Connection connect(String username, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), username, password);
    }

    private static void assertPermissionDenied(Connection connection, Statement statement, String sql)
            throws SQLException {
        var savepoint = connection.setSavepoint();
        assertThatThrownBy(() -> statement.execute(sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
        connection.rollback(savepoint);
    }

    private static void assertWriteRejected(Connection connection, Statement statement, String sql)
            throws SQLException {
        var savepoint = connection.setSavepoint();
        assertThatThrownBy(() -> statement.execute(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(exception.getMessage())
                        .containsAnyOf("permission denied", "read-only transaction"));
        connection.rollback(savepoint);
    }
}
