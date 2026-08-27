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

    @Test
    void runtimeWorkerScopeCoversWarehouseLogisticsAndRejectsStaleDocumentClaims() throws Exception {
        RuntimeSecurityFixture fixture = insertRuntimeSecurityFixture();
        UUID staleToken = UUID.randomUUID();
        try (Connection connection = openRuntimeConnection()) {
            setSessionScope(connection, fixture.scope());
            assertThat(count(connection, "select count(*) from warehouse.warehouse where id = ?", fixture.warehouseId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from logistics.dispatch_number_counter where tenant_id = ? and workspace_id = ?", fixture.scope().tenantId(), fixture.scope().workspaceId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from business_documents.document_generation_request where id = ?", fixture.generationId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from business_documents.evidence_object where id = ?", fixture.evidenceId())).isEqualTo(1);

            setSessionScope(connection, new Scope(UUID.randomUUID(), UUID.randomUUID()));
            assertThat(count(connection, "select count(*) from warehouse.warehouse where id = ?", fixture.warehouseId())).isZero();
            assertThat(count(connection, "select count(*) from logistics.dispatch_number_counter where tenant_id = ? and workspace_id = ?", fixture.scope().tenantId(), fixture.scope().workspaceId())).isZero();
            assertThat(count(connection, "select count(*) from business_documents.evidence_object where id = ?", fixture.evidenceId())).isZero();

            setSessionScope(connection, fixture.scope());
            assertThat(execute(connection, "update business_documents.document_generation_request set status='COMPLETED',claim_token=null where id=? and tenant_id=? and workspace_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp",
                    fixture.generationId(), fixture.scope().tenantId(), fixture.scope().workspaceId(), staleToken)).isZero();
            assertThat(execute(connection, "update business_documents.evidence_object set lifecycle_status='AVAILABLE',claim_token=null,lease_until=null where id=? and tenant_id=? and workspace_id=? and lifecycle_status='SCANNING' and claim_token=? and lease_until > current_timestamp",
                    fixture.evidenceId(), fixture.scope().tenantId(), fixture.scope().workspaceId(), staleToken)).isZero();
            assertThat(execute(connection, "update business_documents.document_generation_request set status='COMPLETED',claim_token=null,lease_until=null where id=? and tenant_id=? and workspace_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp",
                    fixture.generationId(), fixture.scope().tenantId(), fixture.scope().workspaceId(), fixture.currentToken())).isEqualTo(1);
            assertThat(execute(connection, "update business_documents.evidence_object set lifecycle_status='DELETED',claim_token=null,lease_until=null where id=? and tenant_id=? and workspace_id=? and lifecycle_status='SCANNING' and claim_token=? and lease_until > current_timestamp",
                    fixture.evidenceId(), fixture.scope().tenantId(), fixture.scope().workspaceId(), fixture.currentToken())).isEqualTo(1);
        } finally {
            deleteRuntimeSecurityFixture(fixture);
            RlsRequestScope.clear();
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

    private static RuntimeSecurityFixture insertRuntimeSecurityFixture() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        UUID currentToken = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Scope scope = new Scope(tenantId, workspaceId);
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, scope);
                execute(connection, "insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                        tenantId, "RLS worker tenant", "rls-worker-tenant-" + tenantId, now, now);
                execute(connection, "insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                        workspaceId, tenantId, "RLS worker workspace", "rls-worker-workspace-" + workspaceId, now, now);
                execute(connection, "insert into iam.user_account(id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,'ACTIVE',?,?,0)",
                        userId, "rls-worker-" + userId + "@example.test", "rls-worker-" + userId + "@example.test", "rls-worker-" + userId, "rls-worker-" + userId, "RLS worker", "es", now, now);
                execute(connection, "insert into tenant_management.workspace_membership(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'INTERNAL','ACTIVE',?,?,0)",
                        membershipId, workspaceId, userId, now, now);
                execute(connection, "insert into warehouse.warehouse(id,tenant_id,workspace_id,code,name,status,created_at,updated_at) values (?,?,?,?,'RLS worker warehouse','ACTIVE',?,?)",
                        warehouseId, tenantId, workspaceId, "RLS-" + warehouseId.toString().substring(0, 8), now, now);
                execute(connection, "insert into logistics.dispatch_number_counter(tenant_id,workspace_id,dispatch_year,next_value) values (?,?,?,?)",
                        tenantId, workspaceId, now.toLocalDateTime().getYear(), 1L);
                execute(connection, "insert into business_documents.document_generation_request(id,tenant_id,workspace_id,requested_by_membership_id,document_id,subject_type,subject_id,document_type,format,status,idempotency_key,request_hash,attempt_count,requested_at,processing_started_at,lease_until,claim_token) values (?,?,?,?,null,'SALES_ORDER',?,'ORDER_SUMMARY','PDF','PROCESSING',?,?,1,?,?,?,?)",
                        generationId, tenantId, workspaceId, membershipId, subjectId, "rls-worker-generation-" + generationId, "0".repeat(64), now, now, Timestamp.from(Instant.now().plusSeconds(600)), currentToken);
                execute(connection, "insert into business_documents.evidence_object(id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,object_key,lifecycle_status,declared_content_type,original_filename,scan_attempt_count,next_scan_at,created_at,updated_at,lease_until,claim_token) values (?,?,?,null,'SALES_ORDER',?,null,'SCANNING','application/pdf','worker.pdf',1,?,?,?,?,?)",
                        evidenceId, tenantId, workspaceId, subjectId, now, now, now, Timestamp.from(Instant.now().plusSeconds(600)), currentToken);
                connection.commit();
                return new RuntimeSecurityFixture(scope, warehouseId, generationId, evidenceId, currentToken,
                        membershipId, userId, tenantId);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void deleteRuntimeSecurityFixture(RuntimeSecurityFixture fixture) throws SQLException {
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, fixture.scope());
                execute(connection, "delete from business_documents.document_generation_request where id=?", fixture.generationId());
                execute(connection, "delete from business_documents.evidence_object where id=?", fixture.evidenceId());
                execute(connection, "delete from logistics.dispatch_number_counter where tenant_id=? and workspace_id=?", fixture.scope().tenantId(), fixture.scope().workspaceId());
                execute(connection, "delete from warehouse.warehouse where id=?", fixture.warehouseId());
                execute(connection, "delete from tenant_management.workspace_membership where id=?", fixture.membershipId());
                execute(connection, "delete from iam.user_account where id=?", fixture.userId());
                execute(connection, "delete from tenant_management.workspace where id=?", fixture.scope().workspaceId());
                execute(connection, "delete from tenant_management.tenant where id=?", fixture.tenantId());
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void assertRuntimeIdentityAndPrivileges(Connection connection) throws SQLException {
        assertThat(scalar(connection, "select current_user")).as("current_user").isEqualTo(runtimeDatabaseUsername());
        assertThat(scalar(connection, "select session_user")).as("session_user").isEqualTo(runtimeDatabaseUsername());

        try (PreparedStatement statement = connection.prepareStatement("""
                select rolcanlogin, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole,
                       has_schema_privilege(current_user, 'sales', 'USAGE'),
                       has_schema_privilege(current_user, 'sales', 'CREATE'),
                       has_table_privilege(current_user, 'sales.client_account', 'SELECT'),
                       has_table_privilege(current_user, 'sales.client_account', 'TRUNCATE'),
                       d.datdba = pg_roles.oid,
                       not exists (select 1 from pg_class owned
                                    join pg_namespace owned_schema on owned_schema.oid = owned.relnamespace
                                   where owned.relowner = pg_roles.oid
                                     and owned.relkind in ('r', 'p', 'v', 'm', 'f')
                                     and owned_schema.nspname not in ('pg_catalog', 'information_schema'))
                from pg_roles
                join pg_database d on d.datname = current_database()
                where pg_roles.rolname = current_user
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
                assertThat(result.getBoolean(10)).as("runtime role must not own the application database").isFalse();
                assertThat(result.getBoolean(11)).as("runtime role must not own application objects").isTrue();
                assertThat(result.next()).isFalse();
            }
        }

        List<String> forceRlsTables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select n.nspname || '.' || c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where c.relkind = 'r'
                   and c.relrowsecurity
                   and c.relforcerowsecurity
                 order by n.nspname, c.relname
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) forceRlsTables.add(result.getString(1));
            }
        }
        assertThat(forceRlsTables)
                .as("RLS must be enabled and forced for every table used by this isolation proof")
                .containsExactlyInAnyOrder("business_documents.business_document", "business_documents.evidence_object", "business_documents.object_storage_object",
                        "notifications.inbox_item",
                        "payments.credit_account", "payments.credit_reservation", "payments.payment", "payments.payment_attempt", "payments.payment_event", "payments.payment_reconciliation_case", "payments.reconciliation_refund_idempotency", "payments.receivable", "payments.receivable_allocation",
                        "warehouse.warehouse", "warehouse.storage_zone", "warehouse.inventory_lot", "warehouse.stock_movement", "warehouse.inventory_event", "warehouse.inventory_reservation", "warehouse.command_idempotency", "warehouse.warehouse_service_configuration", "warehouse.selection_snapshot", "warehouse.inventory_lot_disposition", "warehouse.inventory_temperature_evaluation", "warehouse.physical_allocation", "warehouse.physical_allocation_line", "warehouse.physical_allocation_event", "warehouse.physical_allocation_command_idempotency",
                        "logistics.dispatch_number_counter", "logistics.dispatch_order", "logistics.dispatch_event", "logistics.command_idempotency", "logistics.proof_of_delivery", "logistics.temperature_reading", "logistics.delivery_incident", "logistics.operational_handoff_note", "logistics.delivery_attempt", "logistics.delivery_attempt_line", "logistics.continuation_delivery", "logistics.continuation_delivery_line", "logistics.fulfillment", "logistics.fulfillment_line", "logistics.fulfillment_command_idempotency", "logistics.fulfillment_event", "logistics.picking_result", "logistics.picking_result_line", "logistics.picking_discrepancy", "logistics.picking_discrepancy_resolution", "logistics.delivery", "logistics.delivery_command_idempotency", "logistics.delivery_assignment", "logistics.delivery_quantity_outcome", "logistics.delivery_event", "logistics.temperature_evidence", "logistics.temperature_excursion", "logistics.proof_of_delivery_addendum",
                        "warehouse.safety_stock_policy", "warehouse.inventory_transfer", "warehouse.inventory_backing", "warehouse.inventory_backing_line", "warehouse.inventory_backing_position", "payments.financial_adjustment", "payments.financial_ledger_entry", "payments.refund_credit_obligation", "payments.receivable_application",
                        "sales.client_account", "sales.client_account_address", "sales.client_account_membership", "sales.commercial_commitment", "sales.commercial_commitment_line", "sales.manual_sales_order_draft", "sales.manual_sales_order_draft_idempotency", "sales.manual_sales_order_draft_line", "sales.purchase_request", "sales.purchase_request_event", "sales.idempotency_record", "sales.idempotency_response", "sales.purchase_request_draft", "sales.purchase_request_draft_destination", "sales.purchase_request_draft_idempotency", "sales.purchase_request_draft_line", "sales.purchase_request_draft_route", "sales.purchase_request_draft_warehouse_selection", "sales.sales_order", "sales.sales_order_event");
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
        UUID tenantThree = UUID.randomUUID();
        List<ScopedRow> rows = new ArrayList<>();
        rows.add(insertRow(tenantOne, UUID.randomUUID(), "one", true));
        rows.add(insertRow(tenantTwo, UUID.randomUUID(), "two", true));
        rows.add(insertRow(tenantThree, UUID.randomUUID(), "three", true));
        return new Fixture(rows, List.of(tenantOne, tenantTwo, tenantThree));
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

    private static int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement.executeUpdate();
        }
    }

    private record Scope(UUID tenantId, UUID workspaceId) { }
    private record ScopedRow(Scope scope, UUID accountId, UUID addressId) { }
    private record Fixture(List<ScopedRow> rows, List<UUID> tenantIds) { }
    private record RuntimeSecurityFixture(Scope scope, UUID warehouseId, UUID generationId, UUID evidenceId,
                                          UUID currentToken, UUID membershipId, UUID userId, UUID tenantId) { }

}
