package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.events.JdbcTenantEventContextQueryAdapter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TenantWorkerCentralScopeRlsIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");
    private static final UUID TENANT_A = new UUID(0, 1);
    private static final UUID WORKSPACE_A = new UUID(0, 2);
    private static final UUID TENANT_B = new UUID(0, 3);
    private static final UUID WORKSPACE_B = new UUID(0, 4);
    private static final Set<String> ROLES = Set.of("sales", "company_owner", "tenant_admin");
    private static HikariDataSource pool;
    private static JdbcTenantEventContextQueryAdapter authority;

    @BeforeAll static void prepare() throws Exception {
        try (Connection connection = POSTGRES.createConnection(""); Statement sql = connection.createStatement()) {
            sql.execute("CREATE ROLE nexa_central_preflight_test LOGIN PASSWORD 'test-only-preflight' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            sql.execute("CREATE SCHEMA tenant_management");
            sql.execute("CREATE TABLE tenant_management.workspace(id uuid primary key,tenant_id uuid not null)");
            sql.execute("CREATE TABLE tenant_management.workspace_membership(id uuid primary key,tenant_id uuid not null,workspace_id uuid not null,status text not null)");
            sql.execute("CREATE TABLE tenant_management.role_definition(id uuid primary key,tenant_id uuid,workspace_id uuid,code text not null,status text not null)");
            sql.execute("CREATE TABLE tenant_management.membership_role_definition(membership_id uuid,tenant_id uuid,workspace_id uuid,role_id uuid)");
            sql.execute("ALTER TABLE tenant_management.workspace ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE tenant_management.workspace FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY exact_workspace ON tenant_management.workspace USING (tenant_id::text=nullif(current_setting('app.current_tenant_id',true),'') AND id::text=nullif(current_setting('app.current_workspace_id',true),''))");
            sql.execute("ALTER TABLE tenant_management.workspace_membership ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE tenant_management.workspace_membership FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY exact_membership ON tenant_management.workspace_membership USING (tenant_id::text=nullif(current_setting('app.current_tenant_id',true),'') AND workspace_id::text=nullif(current_setting('app.current_workspace_id',true),''))");
            sql.execute("GRANT USAGE ON SCHEMA tenant_management TO nexa_central_preflight_test");
            sql.execute("GRANT SELECT ON ALL TABLES IN SCHEMA tenant_management TO nexa_central_preflight_test");
        }
        JdbcTemplate admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        for (String role : ROLES) {
            UUID roleId = UUID.randomUUID();
            admin.update("INSERT INTO tenant_management.role_definition VALUES (?,null,null,?,'ACTIVE')", roleId, role);
            for (UUID[] scope : new UUID[][] {{TENANT_A, WORKSPACE_A}, {TENANT_B, WORKSPACE_B}}) {
                UUID membership = UUID.randomUUID();
                admin.update("INSERT INTO tenant_management.workspace VALUES (?,?) ON CONFLICT DO NOTHING", scope[1], scope[0]);
                admin.update("INSERT INTO tenant_management.workspace_membership VALUES (?,?,?,'ACTIVE')", membership, scope[0], scope[1]);
                admin.update("INSERT INTO tenant_management.membership_role_definition VALUES (?,?,?,?)", membership, scope[0], scope[1], roleId);
            }
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername("nexa_central_preflight_test");
        config.setPassword("test-only-preflight");
        config.setMaximumPoolSize(1);
        config.setReadOnly(true);
        pool = new HikariDataSource(config);
        authority = new JdbcTenantEventContextQueryAdapter(new JdbcTemplate(RlsScopedDataSource.forCurrentCentralScope(pool)));
    }

    @AfterEach void clearScope() { RlsRequestScope.clear(); }
    @AfterAll static void closePool() { if (pool != null) pool.close(); }

    @Test void backgroundPreflightReadsOnlyItsExactScopeAndClearsReusedConnection() {
        assertThat(authority.findActiveMembershipIdsByRoleCodes(TENANT_A, WORKSPACE_A, ROLES)).isEmpty();
        Set<UUID> first = TenantWorkerCentralScope.call(TENANT_A, WORKSPACE_A,
                () -> authority.findActiveMembershipIdsByRoleCodes(TENANT_A, WORKSPACE_A, ROLES));
        assertThat(first).hasSize(3);
        assertThat(RlsRequestScope.current()).isNull();
        assertThat(authority.findActiveMembershipIdsByRoleCodes(TENANT_A, WORKSPACE_A, ROLES)).isEmpty();
        Set<UUID> second = TenantWorkerCentralScope.call(TENANT_B, WORKSPACE_B,
                () -> authority.findActiveMembershipIdsByRoleCodes(TENANT_B, WORKSPACE_B, ROLES));
        assertThat(second).hasSize(3).doesNotContainAnyElementsOf(first);
        assertThat(TenantWorkerCentralScope.call(TENANT_A, WORKSPACE_A,
                () -> authority.findActiveMembershipIdsByRoleCodes(TENANT_B, WORKSPACE_B, ROLES))).isEmpty();
    }

    @Test void restoresPreviousExactScopeAndScanFlagAfterFailure() {
        RlsRequestScope.set(TENANT_A, WORKSPACE_A);
        RlsRequestScope.enableCrossScopeWorkspaceScan();
        assertThatThrownBy(() -> TenantWorkerCentralScope.call(TENANT_A, WORKSPACE_A, () -> {
            assertThat(RlsRequestScope.crossScopeWorkspaceScanEnabled()).isFalse();
            throw new IllegalStateException("test preflight failure");
        })).hasMessage("test preflight failure");
        assertThat(RlsRequestScope.current()).isEqualTo(new RlsRequestScope.Scope(TENANT_A, WORKSPACE_A));
        assertThat(RlsRequestScope.crossScopeWorkspaceScanEnabled()).isTrue();
    }

    @Test void refusesToReplaceAnUnrelatedActiveScope() {
        RlsRequestScope.set(TENANT_A, WORKSPACE_A);
        assertThatThrownBy(() -> TenantWorkerCentralScope.call(TENANT_B, WORKSPACE_B,
                () -> authority.findActiveMembershipIdsByRoleCodes(TENANT_B, WORKSPACE_B, ROLES)))
                .hasMessageContaining("different active scope");
        assertThat(RlsRequestScope.current()).isEqualTo(new RlsRequestScope.Scope(TENANT_A, WORKSPACE_A));
    }
}
