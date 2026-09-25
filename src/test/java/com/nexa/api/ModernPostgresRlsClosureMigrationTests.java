package com.nexa.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Fresh-schema proof for the complete V106 direct-scope RLS inventory. */
@Testcontainers(disabledWithoutDocker = true)
class ModernPostgresRlsClosureMigrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("nexa")
            .withUsername("nexa")
            .withPassword("test-only-password");

    @Test
    void freshSchemaMatchesEveryTableClassificationAndV106Policy() throws Exception {
        try (Connection connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("create role nexa_runtime");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            Map<String, InventoryEntry> inventory = readInventory("v106");
            assertThat(inventory).hasSize(169);
            assertHistoricalV102EvidenceRemainsConsistent();
            assertScopeEvidenceCoversV106ClosureDelta(inventory);
            Map<String, Long> categoryCounts = inventory.values().stream().collect(java.util.stream.Collectors.groupingBy(
                    InventoryEntry::category, java.util.stream.Collectors.counting()));
            assertThat(categoryCounts)
                    .containsEntry("FORCED_RLS_DIRECT_SCOPE", 133L)
                    .containsEntry("INHERITED_SCOPE_JUSTIFIED", 11L)
                    .containsEntry("GLOBAL_REFERENCE", 5L)
                    .containsEntry("GLOBAL_IDENTITY_SECURITY", 7L)
                    .containsEntry("TECHNICAL_GLOBAL", 7L)
                    .containsEntry("CROSS_SCOPE_WORKER", 6L);
            assertThat(categoryCounts.getOrDefault("APPROVED_EXCEPTION", 0L)).isZero();

            Map<String, List<String>> actual = currentTables(connection);
            Map<String, List<String>> expected = new LinkedHashMap<>();
            inventory.forEach((name, row) -> expected.put(name,
                    List.of(row.tenantColumn(), row.workspaceColumn(), row.rlsEnabled(), row.rlsForced())));
            assertThat(actual).as("all current database tables are classified exactly once with live column and RLS facts")
                    .isEqualTo(expected);

            Set<String> forced = new LinkedHashSet<>();
            Set<String> forcedWithWorkspaceScope = new LinkedHashSet<>();
            inventory.forEach((table, row) -> {
                if (row.category().equals("FORCED_RLS_DIRECT_SCOPE")) {
                    assertThat(row.rlsEnabled()).as("RLS enabled: %s", table).isEqualTo("t");
                    assertThat(row.rlsForced()).as("RLS forced: %s", table).isEqualTo("t");
                    forced.add(table);
                    if (row.tenantColumn().equals("t") && row.workspaceColumn().equals("t")) {
                        forcedWithWorkspaceScope.add(table);
                    }
                }
            });
            assertThat(forced).hasSize(133).containsAll(Set.of(
                    "audit.event",
                    "catalog_management.brand", "catalog_management.category", "catalog_management.command_idempotency",
                    "catalog_management.product", "catalog_management.product_asset_reference", "catalog_management.product_family",
                    "catalog_management.product_presentation", "catalog_management.product_price", "catalog_management.product_variant",
                    "catalog_management.product_visibility", "catalog_management.promotion", "catalog_management.promotion_category",
                    "catalog_management.promotion_client_account", "catalog_management.promotion_product", "catalog_management.promotion_rule",
                    "catalog_management.promotion_sku", "catalog_management.seed_import_history", "catalog_management.sellable_sku",
                    "catalog_management.sku_price", "sales.manual_order_idempotency", "sales.sales_order_sequence",
                    "tenant_management.custom_field_definition", "tenant_management.membership_admin_event",
                    "tenant_management.membership_authorization_state", "tenant_management.membership_role_assignment",
                    "tenant_management.membership_role_definition", "tenant_management.organization_invitation",
                    "tenant_management.organization_registration", "tenant_management.role_definition",
                    "tenant_management.workspace_creation_idempotency",
                    "tenant_management.organization_invitation_idempotency", "tenant_management.organization_settings",
                    "tenant_management.reference_plan_assignment", "tenant_management.regional_settings",
                    "tenant_management.tenant_security_settings", "tenant_management.unit_preferences",
                    "tenant_management.tenant", "tenant_management.workspace",
                    "sales.purchase_request_material_change", "warehouse.inventory_transfer_history",
                    "warehouse.stock_temperature_evidence"));

            Set<String> scopedPolicies = tenantWorkspacePolicyTables(connection);
            assertThat(forcedWithWorkspaceScope).hasSize(125);
            assertThat(scopedPolicies).as("each Tenant/Workspace row has USING and WITH CHECK for both scope keys")
                    .containsExactlyInAnyOrderElementsOf(forcedWithWorkspaceScope);
            assertTenantOnlyPolicies(connection);
            assertTenantRootAndWorkspacePolicies(connection);
            assertRuntimePrivileges(connection);
            assertInvitationPolicy(connection);
            assertRegistrationPolicies(connection);
            assertRoleDefinitionPolicies(connection);
        }
    }

    private static Map<String, InventoryEntry> readInventory(String version) throws Exception {
        List<String> lines = Files.readAllLines(Path.of("docs/security/rls-table-inventory-" + version + ".tsv"));
        assertThat(lines.getFirst()).isEqualTo("table\tcategory\ttenant_id\tworkspace_id\trls_enabled\trls_forced\tpolicy");
        assertThat(lines).hasSize(version.equals("v106") ? 170 : 167);
        Map<String, InventoryEntry> result = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            assertThat(fields).hasSize(7);
            assertThat(fields[1]).isIn("FORCED_RLS_DIRECT_SCOPE", "INHERITED_SCOPE_JUSTIFIED", "GLOBAL_REFERENCE",
                    "GLOBAL_IDENTITY_SECURITY", "TECHNICAL_GLOBAL", "CROSS_SCOPE_WORKER", "APPROVED_EXCEPTION");
            assertThat(result.put(fields[0], new InventoryEntry(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6])))
                    .as("table appears once: %s", fields[0]).isNull();
        }
        return result;
    }

    private static void assertHistoricalV102EvidenceRemainsConsistent() throws Exception {
        Map<String, InventoryEntry> historicalV102 = readInventory("v102");
        List<String> baselineLines = Files.readAllLines(Path.of("docs/security/rls-table-inventory-v100.tsv"));
        assertThat(baselineLines.getFirst()).isEqualTo("table\tcategory\ttenant_id\tworkspace_id\trls_enabled\trls_forced\tpolicy");
        Map<String, String> baselineForced = new LinkedHashMap<>();
        for (String line : baselineLines.subList(1, baselineLines.size())) {
            String[] fields = line.split("\t", -1);
            assertThat(fields).hasSize(7);
            baselineForced.put(fields[0], fields[5]);
        }

        Set<String> expectedNewlyForced = new LinkedHashSet<>();
        historicalV102.forEach((table, entry) -> {
            if (entry.category().equals("FORCED_RLS_DIRECT_SCOPE")
                    && !"t".equals(baselineForced.get(table))) expectedNewlyForced.add(table);
        });

        List<String> evidenceLines = Files.readAllLines(Path.of("docs/security/rls-direct-scope-evidence-v102.tsv"));
        assertThat(evidenceLines.getFirst()).isEqualTo("table\tcategory\tscope_source\ttenant_id\tworkspace_id\tparent_derived\tread_write_paths\tworker_path\trls_required\tpolicy_shape\ttest\treason");
        Set<String> evidenced = new LinkedHashSet<>();
        for (String line : evidenceLines.subList(1, evidenceLines.size())) {
            String[] fields = line.split("\t", -1);
            assertThat(fields).hasSize(12);
            assertThat(fields[1]).isEqualTo("FORCED_RLS_DIRECT_SCOPE");
            assertThat(evidenced.add(fields[0])).as("scope evidence appears once: %s", fields[0]).isTrue();
        }
        assertThat(evidenced).as("the historical V102 policy additions retain per-table scope evidence")
                .containsExactlyInAnyOrderElementsOf(expectedNewlyForced);
        assertThat(evidenced.stream().filter(table -> table.startsWith("catalog_management.")).count())
                .as("all 19 V102 Catalog direct-scope tables are individually resolved")
                .isEqualTo(19L);
    }

    private static void assertScopeEvidenceCoversV106ClosureDelta(Map<String, InventoryEntry> current) throws Exception {
        Map<String, InventoryEntry> historicalV102 = readInventory("v102");
        Set<String> newlyForced = new LinkedHashSet<>();
        current.forEach((table, entry) -> {
            if (entry.category().equals("FORCED_RLS_DIRECT_SCOPE")
                    && !historicalV102.containsKey(table)) newlyForced.add(table);
        });
        Set<String> expectedNewlyForced = Set.of(
                "sales.purchase_request_material_change",
                "warehouse.inventory_transfer_history",
                "warehouse.stock_temperature_evidence");
        assertThat(newlyForced).as("V103, V104, and V106 each add one direct-scope table")
                .containsExactlyInAnyOrderElementsOf(expectedNewlyForced);

        List<String> evidenceLines = Files.readAllLines(Path.of("docs/security/rls-direct-scope-evidence-v106.tsv"));
        assertThat(evidenceLines.getFirst()).isEqualTo("table\tcategory\tscope_source\ttenant_id\tworkspace_id\tparent_derived\tread_write_paths\tworker_path\trls_required\tpolicy_shape\ttest\treason");
        Set<String> evidenced = new LinkedHashSet<>();
        for (String line : evidenceLines.subList(1, evidenceLines.size())) {
            String[] fields = line.split("\t", -1);
            assertThat(fields).hasSize(12);
            assertThat(fields[1]).isEqualTo("FORCED_RLS_DIRECT_SCOPE");
            assertThat(expectedNewlyForced).contains(fields[0]);
            assertThat(fields[2]).isNotBlank();
            assertThat(fields[6]).isNotBlank();
            assertThat(fields[7]).isNotBlank();
            assertThat(fields[8]).isEqualTo("yes");
            assertThat(fields[9]).contains("USING+WITH CHECK");
            assertThat(fields[10]).isNotBlank();
            assertThat(evidenced.add(fields[0])).as("V106 scope evidence appears once: %s", fields[0]).isTrue();
        }
        assertThat(evidenced).as("every post-V102 direct-scope table has current per-table evidence")
                .containsExactlyInAnyOrderElementsOf(expectedNewlyForced);
    }

    private static Map<String, List<String>> currentTables(Connection connection) throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select n.nspname || '.' || c.relname, bool_or(a.attname='tenant_id'), bool_or(a.attname='workspace_id'),
                       c.relrowsecurity, c.relforcerowsecurity
                  from pg_class c join pg_namespace n on n.oid=c.relnamespace
                  left join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
                 where c.relkind='r' and n.nspname not in ('pg_catalog','information_schema')
                 group by n.nspname,c.relname,c.relrowsecurity,c.relforcerowsecurity
                 order by 1
                """)) {
            while (rows.next()) result.put(rows.getString(1), List.of(
                    rows.getBoolean(2) ? "t" : "f", rows.getBoolean(3) ? "t" : "f",
                    rows.getBoolean(4) ? "t" : "f", rows.getBoolean(5) ? "t" : "f"));
        }
        return result;
    }

    private static Set<String> tenantWorkspacePolicyTables(Connection connection) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select n.nspname || '.' || c.relname, p.qual, p.with_check
                  from pg_class c join pg_namespace n on n.oid=c.relnamespace
                  join pg_policies p on p.schemaname=n.nspname and p.tablename=c.relname
                 where c.relkind='r' and c.relrowsecurity and c.relforcerowsecurity
                 order by 1
                """)) {
            while (rows.next()) {
                String table = rows.getString(1);
                String using = rows.getString(2);
                String withCheck = rows.getString(3);
                if (using != null && withCheck != null
                        && using.contains("current_setting('app.current_tenant_id'")
                        && using.contains("current_setting('app.current_workspace_id'")
                        && withCheck.contains("current_setting('app.current_tenant_id'")
                        && withCheck.contains("current_setting('app.current_workspace_id'")) result.add(table);
            }
        }
        return result;
    }

    private static void assertRuntimePrivileges(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select has_table_privilege('nexa_runtime','catalog_management.category','SELECT'),
                       has_table_privilege('nexa_runtime','catalog_management.category','INSERT'),
                       has_table_privilege('nexa_runtime','catalog_management.category','UPDATE'),
                       has_table_privilege('nexa_runtime','catalog_management.category','DELETE'),
                       has_table_privilege('nexa_runtime','audit.event','INSERT'),
                       has_table_privilege('nexa_runtime','audit.event','UPDATE'),
                       has_table_privilege('nexa_runtime','audit.event','DELETE'),
                       has_table_privilege('nexa_runtime','audit.event','TRUNCATE'),
                       has_database_privilege('nexa_runtime',current_database(),'CREATE'),
                       has_table_privilege('nexa_runtime','sales.purchase_request_material_change','SELECT'),
                       has_table_privilege('nexa_runtime','sales.purchase_request_material_change','INSERT'),
                       has_table_privilege('nexa_runtime','sales.purchase_request_material_change','UPDATE'),
                       has_table_privilege('nexa_runtime','sales.purchase_request_material_change','DELETE'),
                       has_table_privilege('nexa_runtime','warehouse.stock_temperature_evidence','SELECT'),
                       has_table_privilege('nexa_runtime','warehouse.stock_temperature_evidence','INSERT'),
                       has_table_privilege('nexa_runtime','warehouse.stock_temperature_evidence','UPDATE'),
                       has_table_privilege('nexa_runtime','warehouse.stock_temperature_evidence','DELETE')
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                for (int column = 1; column <= 7; column++) {
                    assertThat(rows.getBoolean(column)).as("V102 retains the already granted runtime DML matrix").isTrue();
                }
                assertThat(rows.getBoolean(8)).as("runtime cannot truncate append-only audit history").isFalse();
                assertThat(rows.getBoolean(9)).as("runtime has no database CREATE authority").isFalse();
                assertThat(rows.getBoolean(10)).as("V104 grants material-change reads").isTrue();
                assertThat(rows.getBoolean(11)).as("V104 grants material-change proposals").isTrue();
                assertThat(rows.getBoolean(12)).as("V104 grants material-change resolution updates").isTrue();
                assertThat(rows.getBoolean(13)).as("V104 does not grant material-change deletes").isFalse();
                assertThat(rows.getBoolean(14)).as("V106 grants stock-temperature evidence reads").isTrue();
                assertThat(rows.getBoolean(15)).as("V106 grants stock-temperature evidence inserts").isTrue();
                assertThat(rows.getBoolean(16)).as("V106 does not grant stock-temperature evidence updates").isFalse();
                assertThat(rows.getBoolean(17)).as("V106 does not grant stock-temperature evidence deletes").isFalse();
            }
        }
    }

    private static void assertTenantOnlyPolicies(Connection connection) throws Exception {
        Set<String> tenantOnly = Set.of("tenant_management.organization_invitation_idempotency",
                "tenant_management.organization_settings", "tenant_management.reference_plan_assignment",
                "tenant_management.regional_settings", "tenant_management.tenant_security_settings",
                "tenant_management.unit_preferences");
        Set<String> policies = tenantIdPolicyTables(connection);
        assertThat(policies).containsExactlyInAnyOrderElementsOf(tenantOnly);
        for (String table : tenantOnly) {
            assertThat(policyExpression(connection, "tenant_management", table.substring("tenant_management.".length()),
                    "v102_tenant_scope", "qual")).contains("current_setting('app.current_tenant_id'")
                    .doesNotContain("app.current_workspace_id");
            assertThat(policyExpression(connection, "tenant_management", table.substring("tenant_management.".length()),
                    "v102_tenant_scope", "with_check")).contains("current_setting('app.current_tenant_id'")
                    .doesNotContain("app.current_workspace_id");
        }
    }

    private static Set<String> tenantIdPolicyTables(Connection connection) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select n.nspname || '.' || c.relname, p.qual, p.with_check
                  from pg_class c join pg_namespace n on n.oid=c.relnamespace
                  join pg_policies p on p.schemaname=n.nspname and p.tablename=c.relname
                 where c.relrowsecurity and c.relforcerowsecurity and p.policyname='v102_tenant_scope'
                 order by 1
                """)) {
            while (rows.next()) {
                if (rows.getString(2) != null && rows.getString(3) != null
                        && rows.getString(2).contains("current_setting('app.current_tenant_id'")
                        && rows.getString(3).contains("current_setting('app.current_tenant_id'")) result.add(rows.getString(1));
            }
        }
        return result;
    }

    private static void assertTenantRootAndWorkspacePolicies(Connection connection) throws Exception {
        assertPolicy(connection, "tenant_management", "tenant", "v102_tenant_root_scope", "ALL",
                "(id)::text", "app.current_tenant_id");
        assertPolicy(connection, "tenant_management", "tenant", "v102_tenant_root_bootstrap_read", "SELECT",
                "app.bootstrap_tenant_slug");
        assertPolicy(connection, "tenant_management", "tenant", "v102_tenant_root_visible_workspace_read", "SELECT",
                "workspace w", "tenant.id");
        assertPolicy(connection, "tenant_management", "workspace", "v102_workspace_tenant_scope", "ALL",
                "(tenant_id)::text", "app.current_tenant_id");
        assertPolicy(connection, "tenant_management", "workspace", "v102_workspace_login_or_preview_slug", "SELECT",
                "app.workspace_lookup_slug");
        assertPolicy(connection, "tenant_management", "workspace", "v102_workspace_membership_revalidation", "SELECT",
                "app.workspace_membership_lookup_id", "app.workspace_membership_lookup_user_id");
        assertPolicy(connection, "tenant_management", "workspace", "v102_workspace_access_context_user", "SELECT",
                "app.access_context_user_id", "tenant_management.workspace_membership", "user_id");
        assertThat(policyExpression(connection, "tenant_management", "workspace",
                "v102_workspace_access_context_user", "qual"))
                .as("identity discovery scope is distinct from the worker scan capability")
                .doesNotContain("app.cross_scope_workspace_scan");
        assertPolicy(connection, "tenant_management", "workspace", "v102_workspace_cross_scope_scan", "SELECT",
                "app.cross_scope_workspace_scan");
    }

    private static void assertInvitationPolicy(Connection connection) throws Exception {
        assertPolicy(connection, "tenant_management", "organization_invitation", "v102_invitation_token_accept", "SELECT",
                "PENDING", "btrim", "app.invitation_accept_token_hash");
    }

    private static void assertRegistrationPolicies(Connection connection) throws Exception {
        assertPolicy(connection, "tenant_management", "organization_registration", "v102_registration_read", "SELECT",
                "app.organization_registration_id", "app.organization_registration_token_hash", "app.organization_registration_operator_id");
        String insert = policyExpression(connection, "tenant_management", "organization_registration", "v102_registration_insert", "with_check");
        assertThat(insert).contains("tenant_id IS NULL", "workspace_id IS NULL", "DRAFT_CREATE", "PUBLIC_SUBMIT");
        assertPolicy(connection, "tenant_management", "organization_registration", "v102_registration_update", "UPDATE",
                "app.current_tenant_id", "app.current_workspace_id", "app.organization_registration_operator_id");
        String updateCheck = policyExpression(connection, "tenant_management", "organization_registration", "v102_registration_update", "with_check");
        assertThat(updateCheck).contains("tenant_id IS NULL", "workspace_id IS NULL", "app.organization_registration_operator_id");
    }

    private static void assertRoleDefinitionPolicies(Connection connection) throws Exception {
        String read = policyExpression(connection, "tenant_management", "role_definition", "v102_role_definition_read", "qual");
        assertThat(read).contains("tenant_id IS NULL", "workspace_id IS NULL", "SYSTEM_RESERVED", "SYSTEM_TEMPLATE",
                        "app.current_tenant_id", "app.current_workspace_id")
                .doesNotContain("status = 'ACTIVE'");
        String insert = policyExpression(connection, "tenant_management", "role_definition", "v102_role_definition_insert", "with_check");
        assertThat(insert).contains("app.current_tenant_id", "workspace_id IS NULL", "app.current_workspace_id", "CUSTOM");
        assertPolicy(connection, "tenant_management", "role_definition", "v102_role_definition_insert", "INSERT",
                "app.current_tenant_id", "app.current_workspace_id");
        assertPolicy(connection, "tenant_management", "role_definition", "v102_role_definition_update", "UPDATE",
                "app.current_tenant_id", "app.current_workspace_id");
        String updateCheck = policyExpression(connection, "tenant_management", "role_definition", "v102_role_definition_update", "with_check");
        assertThat(updateCheck).contains("workspace_id IS NULL", "CUSTOM");
        assertPolicy(connection, "tenant_management", "role_definition", "v102_role_definition_delete", "DELETE",
                "app.current_tenant_id", "app.current_workspace_id");
        assertThat(policyExpression(connection, "tenant_management", "role_definition", "v102_role_definition_delete", "qual"))
                .contains("workspace_id IS NULL", "CUSTOM");
    }

    private static void assertPolicy(Connection connection, String schema, String table, String policy, String command,
                                     String... fragments) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("select cmd,qual,with_check from pg_policies where schemaname=? and tablename=? and policyname=?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, policy);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("policy %s.%s.%s exists", schema, table, policy).isTrue();
                assertThat(rows.getString(1)).isEqualTo(command);
                String expression = rows.getString(2) == null ? rows.getString(3) : rows.getString(2);
                assertThat(expression).contains(fragments);
            }
        }
    }

    private static String policyExpression(Connection connection, String schema, String table, String policy,
                                           String column) throws Exception {
        if (!Set.of("qual", "with_check").contains(column)) throw new IllegalArgumentException("Unsupported policy expression");
        try (PreparedStatement statement = connection.prepareStatement("select " + column + " from pg_policies where schemaname=? and tablename=? and policyname=?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, policy);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("policy %s.%s.%s exists", schema, table, policy).isTrue();
                return rows.getString(1);
            }
        }
    }

    private record InventoryEntry(String category, String tenantColumn, String workspaceColumn,
                                  String rlsEnabled, String rlsForced, String policy) { }
}
