package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.nexa.api.support.PostgresIntegrationSupport.openRuntimeConnection;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SecurityAuditRuntimePrivilegesIT extends PostgresIntegrationSupport {

    @Test
    void runtimeCanAppendGlobalAndScopedEventsButCannotReadOrMutateSecurityAudit() throws Exception {
        String correlationId = "v176-security-audit-" + uuid();
        UUID globalId = UUID.randomUUID();
        UUID scopedId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        try (Connection runtime = openRuntimeConnection()) {
            assertRuntimeIdentityAndPrivileges(runtime);
            assertPermissionDenied(runtime, "select id from iam.security_audit_event limit 1");
            assertPermissionDenied(runtime, "update iam.security_audit_event set metadata_json='{}'::jsonb where false");
            assertPermissionDenied(runtime, "delete from iam.security_audit_event where false");

            insertEvent(runtime, globalId, "V176_GLOBAL_TEST", null, null, correlationId, "global-trace");
            insertEvent(runtime, scopedId, "V176_SCOPED_TEST", tenantId, workspaceId, correlationId, "scoped-trace");
        }

        assertThat(jdbc.queryForObject("select current_user", String.class))
                .as("audit row inspection uses the privileged integration-only datasource")
                .isEqualTo(TEST_DATABASE_USERNAME);
        assertThat(jdbc.queryForObject(
                "select has_table_privilege(current_user, 'iam.security_audit_event', 'SELECT')", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select has_any_column_privilege('nexa_runtime', 'iam.security_audit_event', 'SELECT')",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "select has_any_column_privilege('nexa_runtime', 'iam.security_audit_event', 'UPDATE')",
                Boolean.class)).isFalse();

        List<AuditRow> inserted = jdbc.query("""
                select event_type, tenant_id, workspace_id
                  from iam.security_audit_event
                 where correlation_id = ?
                """, (result, rowNumber) -> new AuditRow(
                result.getString("event_type"),
                result.getObject("tenant_id", UUID.class),
                result.getObject("workspace_id", UUID.class)), correlationId);
        assertThat(inserted).containsExactlyInAnyOrder(
                new AuditRow("V176_GLOBAL_TEST", null, null),
                new AuditRow("V176_SCOPED_TEST", tenantId, workspaceId));
    }

    private static void assertRuntimeIdentityAndPrivileges(Connection runtime) throws SQLException {
        try (Statement statement = runtime.createStatement();
             ResultSet result = statement.executeQuery("""
                     select current_user, session_user,
                            has_table_privilege(current_user, 'iam.security_audit_event', 'SELECT'),
                            has_any_column_privilege(current_user, 'iam.security_audit_event', 'SELECT'),
                            has_table_privilege(current_user, 'iam.security_audit_event', 'INSERT'),
                            has_table_privilege(current_user, 'iam.security_audit_event', 'UPDATE'),
                            has_any_column_privilege(current_user, 'iam.security_audit_event', 'UPDATE'),
                            has_table_privilege(current_user, 'iam.security_audit_event', 'DELETE')
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(RUNTIME_USERNAME);
            assertThat(result.getString(2)).isEqualTo(RUNTIME_USERNAME);
            assertThat(result.getBoolean(3)).isFalse();
            assertThat(result.getBoolean(4)).isFalse();
            assertThat(result.getBoolean(5)).isTrue();
            assertThat(result.getBoolean(6)).isFalse();
            assertThat(result.getBoolean(7)).isFalse();
            assertThat(result.getBoolean(8)).isFalse();
        }
    }

    private static void assertPermissionDenied(Connection runtime, String sql) throws SQLException {
        try (Statement statement = runtime.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql))
                    .isInstanceOf(SQLException.class)
                    .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("42501"));
        }
    }

    private static void insertEvent(Connection runtime, UUID id, String eventType, UUID tenantId, UUID workspaceId,
                                    String correlationId, String traceId) throws SQLException {
        try (PreparedStatement statement = runtime.prepareStatement("""
                insert into iam.security_audit_event
                    (id, event_type, tenant_id, workspace_id, correlation_id, trace_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, current_timestamp)
                """)) {
            statement.setObject(1, id, Types.OTHER);
            statement.setString(2, eventType);
            statement.setObject(3, tenantId, Types.OTHER);
            statement.setObject(4, workspaceId, Types.OTHER);
            statement.setString(5, correlationId);
            statement.setString(6, traceId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private record AuditRow(String eventType, UUID tenantId, UUID workspaceId) { }
}
