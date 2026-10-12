package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogFamilySkuMappingLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceSeedLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogSkuPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogVariantMappingLoader;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in rehearsal of the V3 schema projection and catalog fixture extraction. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantBusinessDatabaseBaselineIT {
    private static final String MIGRATOR_PASSWORD = "tenant-migrator-only-test-password";
    private static final String RUNTIME_PASSWORD = "tenant-runtime-only-test-password";
    private static final String CENTRAL_RUNTIME_PASSWORD = "central-runtime-only-test-password";
    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Pattern VARCHAR_ARRAY_TO_TEXT_ARRAY = Pattern.compile("\\(\\((ARRAY\\[.*?\\])\\)::text\\[\\]\\)");
    private static final Pattern VARCHAR_LITERAL_TO_TEXT = Pattern.compile("\\('([^']*)'::character varying\\)::text");

    @Container
    private static final PostgreSQLContainer CENTRAL = container("nexa-v3-central");
    @Container
    private static final PostgreSQLContainer TENANT_A = container("nexa-v3-tenant-a");
    @Container
    private static final PostgreSQLContainer TENANT_B = container("nexa-v3-tenant-b");

    @Test
    void v3ProjectsCurrentBusinessSchemaAndReconcilesTheReviewedCatalogFixture() throws Exception {
        createRuntimeRole(CENTRAL, CENTRAL_RUNTIME_PASSWORD);
        migrateCentral();

        List<TenantBusinessDatabaseBaselineGenerator.Table> manifest = TenantBusinessDatabaseBaselineGenerator.readManifest(REPOSITORY_ROOT);
        assertThat(manifest).hasSize(167);
        assertThat(manifest.stream().filter(table -> table.owner().matches("BC(0[2-9]|1[01])")).count()).isEqualTo(147);
        assertThat(manifest.stream().filter(table -> table.owner().equals("SHARED_TECHNICAL")).count()).isEqualTo(16);
        assertThat(manifest.stream().filter(table -> table.owner().equals("GLOBAL_REFERENCE")).count()).isEqualTo(4);
        String generated = TenantBusinessDatabaseBaselineGenerator.generate(CENTRAL, REPOSITORY_ROOT);
        Path migration = REPOSITORY_ROOT.resolve(TenantBusinessDatabaseBaselineGenerator.OUTPUT_PATH);
        if (Boolean.getBoolean("nexa.tenant.baseline.generate")) {
            Files.writeString(migration, generated, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(migration)).as("V3 migration is checked in").isTrue();
        assertThat(Files.readString(migration, StandardCharsets.UTF_8)).isEqualTo(generated);

        provisionTenantRoles(TENANT_A);
        provisionTenantRoles(TENANT_B);
        migrateTenant(TENANT_A);
        migrateTenant(TENANT_B);

        UUID tenantId = UUID.fromString("218f7b0c-5c44-43c0-a8f6-44ca3bb9a3cb");
        UUID workspaceId = UUID.fromString("ca25033c-8f07-44f6-b88f-fafde2952c80");
        UUID databaseIdentity = UUID.fromString("2c9c16e2-821d-4b80-b62e-1223a7e78209");
        UUID secondTenantId = UUID.fromString("65aefc79-515a-4fd1-ade1-3a7342e1b2c2");
        UUID secondWorkspaceId = UUID.fromString("04b1173c-3a6a-40a7-9605-163d5309cd11");
        UUID secondDatabaseIdentity = UUID.fromString("098b3e85-9d77-4937-bcd2-6d03b8bb4ef6");
        seedTenantScope(TENANT_A, tenantId, workspaceId, databaseIdentity);
        seedTenantScope(TENANT_B, secondTenantId, secondWorkspaceId, secondDatabaseIdentity);

        assertTenantSchemaShape(CENTRAL, TENANT_A, manifest);
        assertTenantSchemaShape(CENTRAL, TENANT_B, manifest);
        assertTenantGrants(TENANT_A, manifest, tenantId, workspaceId, databaseIdentity);
        assertTenantGrants(TENANT_B, manifest, secondTenantId, secondWorkspaceId, secondDatabaseIdentity);
        assertGlobalReferenceProjection(CENTRAL, TENANT_A);
        assertGlobalReferenceProjection(CENTRAL, TENANT_B);

        seedReviewedCatalog(CENTRAL, tenantId, workspaceId);
        List<String> catalogTables = manifest.stream()
                .filter(table -> table.name().startsWith("catalog_management."))
                .map(TenantBusinessDatabaseBaselineGenerator.Table::name).toList();
        List<String> orderedCatalogTables = topologicalTables(TENANT_A, catalogTables);
        copyTables(CENTRAL, TENANT_A, orderedCatalogTables);
        assertCatalogExtraction(CENTRAL, TENANT_A, TENANT_B, catalogTables, tenantId, workspaceId);
    }

    private static void migrateCentral() {
        Flyway.configure().dataSource(CENTRAL.getJdbcUrl(), CENTRAL.getUsername(), CENTRAL.getPassword())
                .locations("classpath:db/migration").target("146").load().migrate();
    }

    private static void migrateTenant(PostgreSQLContainer tenant) {
        Flyway.configure().dataSource(tenant.getJdbcUrl(), TenantBusinessDatabaseBaselineGenerator.MIGRATOR_ROLE, MIGRATOR_PASSWORD)
                .locations("filesystem:" + REPOSITORY_ROOT.resolve("src/main/resources/db/tenant-migration"))
                .target("3")
                .load().migrate();
    }

    private static void createRuntimeRole(PostgreSQLContainer container, String password) throws SQLException {
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE nexa_runtime LOGIN PASSWORD '" + password
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        }
    }

    private static void provisionTenantRoles(PostgreSQLContainer tenant) throws SQLException {
        try (Connection connection = adminConnection(tenant); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE nexa_migrator LOGIN PASSWORD '" + MIGRATOR_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_runtime LOGIN PASSWORD '" + RUNTIME_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + tenant.getDatabaseName() + "\" TO nexa_migrator");
            statement.execute("GRANT CONNECT ON DATABASE \"" + tenant.getDatabaseName() + "\" TO nexa_runtime");
            statement.execute("GRANT CREATE ON SCHEMA public TO nexa_migrator");
        }
    }

    private static void seedTenantScope(PostgreSQLContainer tenant, UUID tenantId, UUID workspaceId, UUID databaseIdentity)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(tenant.getJdbcUrl(), TenantBusinessDatabaseBaselineGenerator.MIGRATOR_ROLE, MIGRATOR_PASSWORD);
             PreparedStatement identity = connection.prepareStatement("""
                     INSERT INTO nexa_platform.tenant_business_database_identity(singleton, tenant_id, database_identity)
                     VALUES (true, ?, ?)
                     """);
             PreparedStatement anchor = connection.prepareStatement("""
                     INSERT INTO nexa_platform.tenant_workspace_scope_anchor(tenant_id, workspace_id)
                     VALUES (?, ?)
                     """)) {
            identity.setObject(1, tenantId); identity.setObject(2, databaseIdentity); identity.executeUpdate();
            anchor.setObject(1, tenantId); anchor.setObject(2, workspaceId); anchor.executeUpdate();
        }
    }

    private static void assertTenantSchemaShape(PostgreSQLContainer source, PostgreSQLContainer tenant,
            List<TenantBusinessDatabaseBaselineGenerator.Table> manifest) throws SQLException {
        Set<String> expected = manifest.stream().map(TenantBusinessDatabaseBaselineGenerator.Table::name)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> actual = relationNames(tenant);
        Set<String> expectedRelations = new TreeSet<>(expected);
        expectedRelations.add("nexa_platform.tenant_business_database_identity");
        expectedRelations.add("nexa_platform.tenant_workspace_scope_anchor");
        expectedRelations.add("public.flyway_schema_history");
        assertThat(actual).containsExactlyElementsOf(expectedRelations);
        assertThat(actual).noneMatch(name -> name.startsWith("iam."));
        assertThat(actual).noneMatch(name -> Set.of("tenant_management.tenant", "tenant_management.workspace",
                "tenant_management.workspace_membership", "tenant_management.tenant_business_database_binding").contains(name));

        assertThat(foreignKeyTargets(tenant)).allMatch(target -> target.startsWith("nexa_platform.")
                || expected.contains(target));
        assertThat(foreignKeyCount(source, expected)).isGreaterThan(0);
        assertThat(externalActorForeignKeyCount(tenant)).isZero();
        assertThat(shapeDifferences(tableColumnShape(source, expected), tableColumnShape(tenant, expected)))
                .as("column shape differences (source => Tenant)").isEmpty();
        assertThat(shapeDifferences(tableNonForeignConstraintShape(source, expected), tableNonForeignConstraintShape(tenant, expected)))
                .as("non-FK constraint shape differences (source => Tenant)").isEmpty();
        assertThat(foreignKeyNameShape(source, expected, true)).isEqualTo(foreignKeyNameShape(tenant, expected, false));
        assertThat(shapeDifferences(tableIndexShape(source, expected), tableIndexShape(tenant, expected)))
                .as("index shape differences (source => Tenant)").isEmpty();
        assertThat(tableSequenceShape(source, expected)).isEqualTo(tableSequenceShape(tenant, expected));
        assertThat(runtimeSequenceGrantShape(source, expected)).isEqualTo(runtimeSequenceGrantShape(tenant, expected));
        assertThat(tablePolicyShape(source, expected)).isEqualTo(tablePolicyShape(tenant, expected));
        assertThat(tableTriggerShape(source, expected)).isEqualTo(tableTriggerShape(tenant, expected));
        assertThat(tableRlsShape(source, expected)).isEqualTo(tableRlsShape(tenant, expected));
        assertThat(businessFunctionShape(source, manifest)).isEqualTo(businessFunctionShape(tenant, manifest));
        assertThat(businessFunctionAclShape(source, manifest)).isEqualTo(businessFunctionAclShape(tenant, manifest));
        assertThat(runtimeTableGrantShape(source, expected)).isEqualTo(runtimeTableGrantShape(tenant, expected));
    }

    private static Set<String> relationNames(PostgreSQLContainer container) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT n.nspname || '.' || c.relname
                     FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                     WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('pg_catalog','information_schema')
                       AND n.nspname NOT LIKE 'pg_toast%'
                     ORDER BY 1
                     """)) {
            while (result.next()) names.add(result.getString(1));
        }
        return names;
    }

    private static List<String> foreignKeyTargets(PostgreSQLContainer container) throws SQLException {
        List<String> targets = new ArrayList<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT DISTINCT target_ns.nspname || '.' || target.relname
                     FROM pg_constraint c JOIN pg_class target ON target.oid=c.confrelid
                     JOIN pg_namespace target_ns ON target_ns.oid=target.relnamespace
                     WHERE c.contype='f' ORDER BY 1
                     """)) {
            while (result.next()) targets.add(result.getString(1));
        }
        return targets;
    }

    private static int foreignKeyCount(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        int count = 0;
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT n.nspname || '.' || t.relname
                     FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                     WHERE c.contype='f'
                     """)) {
            while (result.next()) if (tables.contains(result.getString(1))) count++;
        }
        return count;
    }

    private static int externalActorForeignKeyCount(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT count(*) FROM pg_constraint c
                     JOIN pg_class target ON target.oid=c.confrelid JOIN pg_namespace n ON n.oid=target.relnamespace
                     WHERE c.contype='f' AND ((n.nspname='iam' AND target.relname='user_account')
                       OR (n.nspname='tenant_management' AND target.relname='workspace_membership'))
                     """)) {
            result.next(); return result.getInt(1);
        }
    }

    private static Map<String, String> tableColumnShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT table_schema || '.' || table_name,
                            row_number() OVER (PARTITION BY table_schema, table_name ORDER BY ordinal_position),
                            column_name, data_type, udt_name,
                            is_nullable, column_default, is_identity, identity_generation
                     FROM information_schema.columns
                     WHERE table_schema || '.' || table_name IN (%s)
                     ORDER BY table_schema, table_name, ordinal_position
                     """.formatted(sqlStringSet(tables)))) {
            while (result.next()) shape.put(result.getString(1) + "." + result.getInt(2),
                    result.getString(3) + "|" + result.getString(4) + "|" + result.getString(5) + "|"
                            + result.getString(6) + "|" + result.getString(7) + "|" + result.getString(8) + "|" + result.getString(9));
        }
        return shape;
    }

    private static Map<String, String> tableNonForeignConstraintShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT n.nspname || '.' || t.relname || '.' || c.conname, pg_get_constraintdef(c.oid, true)
                     FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                     WHERE c.contype <> 'f' AND n.nspname || '.' || t.relname IN (%s)
                     ORDER BY 1
                     """.formatted(sqlStringSet(tables)))) {
            while (result.next()) shape.put(result.getString(1), normalizeConstraintDefinition(result.getString(2)));
        }
        return shape;
    }

    private static String normalizeConstraintDefinition(String definition) {
        // pg_dump re-emits string-array CHECKs with redundant varchar-to-text element casts.
        return definition.replace("::character varying::text", "::character varying")
                .replace("::text[]", "");
    }

    private static Set<String> foreignKeyNameShape(PostgreSQLContainer container, Set<String> tables,
            boolean omitCentralActorReferences) throws SQLException {
        Set<String> shape = new TreeSet<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT source_ns.nspname || '.' || source_table.relname || '.' || c.conname,
                            target_ns.nspname || '.' || target_table.relname
                     FROM pg_constraint c JOIN pg_class source_table ON source_table.oid=c.conrelid
                     JOIN pg_namespace source_ns ON source_ns.oid=source_table.relnamespace
                     JOIN pg_class target_table ON target_table.oid=c.confrelid
                     JOIN pg_namespace target_ns ON target_ns.oid=target_table.relnamespace
                     WHERE c.contype='f' AND source_ns.nspname || '.' || source_table.relname IN (%s)
                     ORDER BY 1
                     """.formatted(sqlStringSet(tables)))) {
            while (result.next()) {
                String constraint = result.getString(1), target = result.getString(2);
                if (omitCentralActorReferences && (target.equals("iam.user_account")
                        || target.equals("tenant_management.workspace_membership"))) continue;
                shape.add(constraint);
            }
        }
        return shape;
    }

    private static Map<String, String> tableIndexShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT n.nspname || '.' || t.relname || '.' || i.relname, pg_get_indexdef(i.oid)
                     FROM pg_index x JOIN pg_class t ON t.oid=x.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                     JOIN pg_class i ON i.oid=x.indexrelid
                     WHERE n.nspname || '.' || t.relname IN (%s)
                     ORDER BY 1
                     """.formatted(sqlStringSet(tables)))) {
            while (result.next()) shape.put(result.getString(1), normalizeIndexDefinition(result.getString(2)));
        }
        return shape;
    }

    private static String normalizeIndexDefinition(String definition) {
        // PostgreSQL can deparse the same varchar[] -> text[] predicate cast either as
        // one array cast or as text casts on each literal. Compare the predicate's
        // stable expression while retaining all index keys, options, and operators.
        String normalized = VARCHAR_ARRAY_TO_TEXT_ARRAY.matcher(definition).replaceAll("($1)");
        return VARCHAR_LITERAL_TO_TEXT.matcher(normalized).replaceAll("'$1'::character varying");
    }

    private static Map<String, String> tablePolicyShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT schemaname || '.' || tablename || '.' || policyname,
                            permissive, roles::text, cmd, coalesce(qual,''), coalesce(with_check,'')
                     FROM pg_policies
                     WHERE schemaname || '.' || tablename IN (%s)
                     ORDER BY 1
                     """.formatted(sqlStringSet(tables)))) {
            while (result.next()) shape.put(result.getString(1), String.join("|", result.getString(2), result.getString(3),
                    result.getString(4), result.getString(5), result.getString(6)));
        }
        return shape;
    }

    private static Map<String, String> tableTriggerShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT n.nspname || '.' || t.relname || '.' || g.tgname,
                            pg_get_triggerdef(g.oid, true), g.tgenabled
                     FROM pg_trigger g JOIN pg_class t ON t.oid=g.tgrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                     WHERE NOT g.tgisinternal AND n.nspname || '.' || t.relname IN (%s)
                     ORDER BY 1
                     """.formatted(sqlStringSet(tables)))) {
            while (result.next()) shape.put(result.getString(1), result.getString(2) + "|" + result.getString(3));
        }
        return shape;
    }

    private static Map<String, String> tableRlsShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT n.nspname || '.' || c.relname, c.relrowsecurity, c.relforcerowsecurity
                     FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                     WHERE c.relkind IN ('r','p') AND n.nspname || '.' || c.relname IN (%s)
                     ORDER BY 1
                     """.formatted(sqlStringSet(tables)))) {
            while (result.next()) shape.put(result.getString(1), result.getBoolean(2) + "|" + result.getBoolean(3));
        }
        return shape;
    }

    private static Map<String, String> tableSequenceShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT sequence_ns.nspname || '.' || sequence_row.relname,
                            format_type(sequence_definition.seqtypid,NULL), sequence_definition.seqstart,
                            sequence_definition.seqincrement, sequence_definition.seqmin, sequence_definition.seqmax,
                            sequence_definition.seqcache, sequence_definition.seqcycle,
                            table_ns.nspname || '.' || table_row.relname || '.' || attribute.attname
                     FROM pg_class sequence_row JOIN pg_namespace sequence_ns ON sequence_ns.oid=sequence_row.relnamespace
                     JOIN pg_sequence sequence_definition ON sequence_definition.seqrelid=sequence_row.oid
                     JOIN pg_depend dependency ON dependency.objid=sequence_row.oid AND dependency.classid='pg_class'::regclass
                     JOIN pg_class table_row ON table_row.oid=dependency.refobjid
                     JOIN pg_namespace table_ns ON table_ns.oid=table_row.relnamespace
                     JOIN pg_attribute attribute ON attribute.attrelid=table_row.oid AND attribute.attnum=dependency.refobjsubid
                     WHERE sequence_row.relkind='S' AND dependency.refclassid='pg_class'::regclass
                       AND dependency.deptype IN ('a','i')
                     ORDER BY 1
                     """)) {
            while (result.next()) {
                String owningTable = result.getString(9);
                if (!tables.contains(owningTable)) continue;
                shape.put(result.getString(1), String.join("|", result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6), result.getString(7),
                        result.getString(8), owningTable));
            }
        }
        return shape;
    }

    private static Map<String, String> runtimeSequenceGrantShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT sequence_ns.nspname || '.' || sequence_row.relname,
                            table_ns.nspname || '.' || table_row.relname
                     FROM pg_class sequence_row JOIN pg_namespace sequence_ns ON sequence_ns.oid=sequence_row.relnamespace
                     JOIN pg_depend dependency ON dependency.objid=sequence_row.oid AND dependency.classid='pg_class'::regclass
                     JOIN pg_class table_row ON table_row.oid=dependency.refobjid
                     JOIN pg_namespace table_ns ON table_ns.oid=table_row.relnamespace
                     WHERE sequence_row.relkind='S' AND dependency.refclassid='pg_class'::regclass
                       AND dependency.deptype IN ('a','i') ORDER BY 1
                     """)) {
            while (result.next()) {
                String sequence = result.getString(1);
                if (!tables.contains(result.getString(2))) continue;
                for (String privilege : List.of("USAGE", "SELECT", "UPDATE")) {
                    try (PreparedStatement privilegeStatement = connection.prepareStatement("SELECT has_sequence_privilege('nexa_runtime', ?, ?)")) {
                        privilegeStatement.setString(1, sequence); privilegeStatement.setString(2, privilege);
                        try (ResultSet privilegeRow = privilegeStatement.executeQuery()) {
                            privilegeRow.next();
                            if (privilegeRow.getBoolean(1)) shape.put(sequence + "|" + privilege, "granted");
                        }
                    }
                }
            }
        }
        return shape;
    }

    private static Map<String, String> businessFunctionShape(PostgreSQLContainer container,
            List<TenantBusinessDatabaseBaselineGenerator.Table> manifest) throws SQLException {
        Set<String> schemas = manifest.stream().map(table -> table.name().substring(0, table.name().indexOf('.')))
                .filter(schema -> !schema.equals("reference_data") && !schema.equals("tenant_management"))
                .collect(Collectors.toCollection(TreeSet::new));
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); PreparedStatement statement = connection.prepareStatement("""
                SELECT n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')', pg_get_functiondef(p.oid)
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE p.prokind='f' AND n.nspname = ANY (?) ORDER BY 1
                """)) {
            statement.setArray(1, connection.createArrayOf("text", schemas.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) { while (result.next()) shape.put(result.getString(1), result.getString(2)); }
        }
        return shape;
    }

    private static Map<String, String> businessFunctionAclShape(PostgreSQLContainer container,
            List<TenantBusinessDatabaseBaselineGenerator.Table> manifest) throws SQLException {
        Set<String> schemas = manifest.stream().map(table -> table.name().substring(0, table.name().indexOf('.')))
                .filter(schema -> !schema.equals("reference_data") && !schema.equals("tenant_management"))
                .collect(Collectors.toCollection(TreeSet::new));
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); PreparedStatement statement = connection.prepareStatement("""
                SELECT n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
                       EXISTS (SELECT 1 FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
                               WHERE acl.grantee=0 AND acl.privilege_type='EXECUTE'),
                       EXISTS (SELECT 1 FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
                               WHERE acl.grantee='nexa_runtime'::regrole AND acl.privilege_type='EXECUTE')
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE p.prokind='f' AND n.nspname = ANY (?) ORDER BY 1
                """)) {
            statement.setArray(1, connection.createArrayOf("text", schemas.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) shape.put(result.getString(1), result.getBoolean(2) + "|" + result.getBoolean(3));
            }
        }
        return shape;
    }

    private static Map<String, String> runtimeTableGrantShape(PostgreSQLContainer container, Set<String> tables) throws SQLException {
        Map<String, String> shape = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container); PreparedStatement statement = connection.prepareStatement("""
                SELECT n.nspname || '.' || c.relname, privilege
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN']) privilege
                WHERE c.relkind IN ('r','p') AND n.nspname || '.' || c.relname = ANY (?)
                ORDER BY 1,2
                """)) {
            statement.setArray(1, connection.createArrayOf("text", tables.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String table = result.getString(1), privilege = result.getString(2);
                    if (hasTablePrivilege(connection, table, privilege)) shape.put(table + "|" + privilege, "granted");
                }
            }
        }
        return shape;
    }

    private static String sqlStringSet(Set<String> values) {
        return values.stream().sorted().map(value -> "'" + value.replace("'", "''") + "'").collect(Collectors.joining(","));
    }

    private static Map<String, String> shapeDifferences(Map<String, String> source, Map<String, String> tenant) {
        Set<String> keys = new TreeSet<>(source.keySet());
        keys.addAll(tenant.keySet());
        Map<String, String> differences = new LinkedHashMap<>();
        for (String key : keys) {
            String sourceValue = source.get(key), tenantValue = tenant.get(key);
            if (!java.util.Objects.equals(sourceValue, tenantValue)) {
                differences.put(key, "source=" + sourceValue + "; tenant=" + tenantValue);
            }
        }
        return differences;
    }

    private static void assertTenantGrants(PostgreSQLContainer tenant,
            List<TenantBusinessDatabaseBaselineGenerator.Table> manifest, UUID tenantId,
            UUID workspaceId, UUID databaseIdentity) throws SQLException {
        try (Connection connection = DriverManager.getConnection(tenant.getJdbcUrl(), TenantBusinessDatabaseBaselineGenerator.RUNTIME_ROLE, RUNTIME_PASSWORD)) {
            assertThat(hasTablePrivilege(connection, "nexa_platform.tenant_business_database_identity", "SELECT")).isTrue();
            assertThat(hasTablePrivilege(connection, "nexa_platform.tenant_workspace_scope_anchor", "SELECT")).isTrue();
            assertThat(hasTablePrivilege(connection, TenantBusinessDatabaseBaselineGenerator.MIGRATOR_ROLE, "catalog_management.product", "INSERT")).isTrue();
            assertThat(scalarLong(connection, "SELECT count(*) FROM nexa_platform.tenant_business_database_identity")).isEqualTo(1);
            assertThat(scalarLong(connection, "SELECT count(*) FROM nexa_platform.tenant_workspace_scope_anchor")).isEqualTo(1);
            assertThat(scalarLong(connection, "SELECT count(*) FROM catalog_management.product")).isZero();
            try (PreparedStatement identity = connection.prepareStatement("""
                    SELECT count(*) FROM nexa_platform.tenant_business_database_identity
                    WHERE tenant_id=? AND database_identity=?
                    """);
                 PreparedStatement anchor = connection.prepareStatement("""
                    SELECT count(*) FROM nexa_platform.tenant_workspace_scope_anchor
                    WHERE tenant_id=? AND workspace_id=?
                    """)) {
                identity.setObject(1, tenantId); identity.setObject(2, databaseIdentity);
                anchor.setObject(1, tenantId); anchor.setObject(2, workspaceId);
                try (ResultSet row = identity.executeQuery()) { row.next(); assertThat(row.getLong(1)).isEqualTo(1); }
                try (ResultSet row = anchor.executeQuery()) { row.next(); assertThat(row.getLong(1)).isEqualTo(1); }
            }
            for (String table : List.of("nexa_platform.tenant_business_database_identity", "nexa_platform.tenant_workspace_scope_anchor")) {
                for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    assertThat(hasTablePrivilege(connection, table, privilege)).as("runtime %s on %s", privilege, table).isFalse();
                }
            }
            for (String table : GLOBAL_REFERENCE_TABLES) {
                assertThat(hasTablePrivilege(connection, table, "SELECT")).isTrue();
                assertThat(scalarLong(connection, "SELECT count(*) FROM " + quoteQualified(table))).isPositive();
                for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    assertThat(hasTablePrivilege(connection, table, privilege)).as("global reference runtime %s on %s", privilege, table).isFalse();
                }
            }
            assertThatThrownBy(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE reference_data.department SET name=name WHERE code='15'");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE nexa_platform.tenant_workspace_scope_anchor SET workspace_id=workspace_id");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    private static void assertGlobalReferenceProjection(PostgreSQLContainer source, PostgreSQLContainer tenant) throws SQLException {
        try (Connection sourceConnection = adminConnection(source); Connection targetConnection = adminConnection(tenant)) {
            for (String table : GLOBAL_REFERENCE_TABLES) {
                List<String> columns = columnsExceptLoadedAt(sourceConnection, table);
                assertThat(referenceChecksum(sourceConnection, table, columns)).isEqualTo(referenceChecksum(targetConnection, table, columns));
                assertThat(rowCount(targetConnection, table)).isEqualTo(rowCount(sourceConnection, table));
            }
        }
    }

    private static List<String> columnsExceptLoadedAt(Connection connection, String table) throws SQLException {
        String[] parts = table.split("\\.", 2);
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=?
                  AND column_name <> 'loaded_at' ORDER BY ordinal_position
                """)) {
            statement.setString(1, parts[0]); statement.setString(2, parts[1]);
            try (ResultSet result = statement.executeQuery()) { while (result.next()) columns.add(result.getString(1)); }
        }
        return columns;
    }

    private static String referenceChecksum(Connection connection, String table, List<String> columns) throws SQLException {
        List<String> keys = primaryKeyColumns(connection, table);
        String select = columns.stream().map(TenantBusinessDatabaseBaselineIT::quote).collect(Collectors.joining(", "));
        String order = keys.stream().map(TenantBusinessDatabaseBaselineIT::quote).collect(Collectors.joining(", "));
        StringBuilder canonical = new StringBuilder();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT " + select + " FROM " + quoteQualified(table) + " ORDER BY " + order)) {
            while (result.next()) {
                for (int index = 1; index <= columns.size(); index++) {
                    String value = result.getString(index);
                    canonical.append(value == null ? "-1:" : value.length() + ":" + value).append('|');
                }
                canonical.append('\n');
            }
        }
        return sha256(canonical.toString());
    }

    private static long rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT count(*) FROM " + quoteQualified(table))) {
            result.next(); return result.getLong(1);
        }
    }

    private static long scalarLong(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            result.next(); return result.getLong(1);
        }
    }

    private static List<String> primaryKeyColumns(Connection connection, String table) throws SQLException {
        String[] parts = table.split("\\.", 2);
        List<String> keys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT attribute.attname
                FROM pg_constraint c JOIN pg_class relation ON relation.oid=c.conrelid
                JOIN pg_namespace namespace_row ON namespace_row.oid=relation.relnamespace
                CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS key_column(attnum, ordinality)
                JOIN pg_attribute attribute ON attribute.attrelid=relation.oid AND attribute.attnum=key_column.attnum
                WHERE c.contype='p' AND namespace_row.nspname=? AND relation.relname=?
                ORDER BY key_column.ordinality
                """)) {
            statement.setString(1, parts[0]); statement.setString(2, parts[1]);
            try (ResultSet result = statement.executeQuery()) { while (result.next()) keys.add(result.getString(1)); }
        }
        return keys;
    }

    private static void seedReviewedCatalog(PostgreSQLContainer central, UUID tenantId, UUID workspaceId) throws Exception {
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        try (Connection connection = adminConnection(central); PreparedStatement tenant = connection.prepareStatement("""
                     INSERT INTO tenant_management.tenant(id,name,slug,status,created_at,updated_at)
                     VALUES (?, 'V3 fixture tenant', 'v3-fixture-tenant', 'ACTIVE', ?, ?)
                     """);
             PreparedStatement workspace = connection.prepareStatement("""
                     INSERT INTO tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at)
                     VALUES (?, ?, 'V3 fixture workspace', 'v3-fixture-workspace', 'ACTIVE', ?, ?)
                     """)) {
            tenant.setObject(1, tenantId); tenant.setObject(2, now); tenant.setObject(3, now); tenant.executeUpdate();
            workspace.setObject(1, workspaceId); workspace.setObject(2, tenantId); workspace.setObject(3, now); workspace.setObject(4, now); workspace.executeUpdate();
        }
        DataSource source = adminDataSource(central);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        WorkspaceDirectory directory = new WorkspaceDirectory() {
            private final WorkspaceDirectory.Scope scope = new WorkspaceDirectory.Scope(tenantId, workspaceId);
            @Override public boolean exists(UUID tenant, UUID workspace) { return tenantId.equals(tenant) && workspaceId.equals(workspace); }
            @Override public List<WorkspaceDirectory.Scope> scanAfter(UUID cursorTenant, UUID cursorWorkspace, int limit) {
                return cursorTenant == null ? List.of(scope) : List.of();
            }
            @Override public List<WorkspaceDirectory.Scope> scanActiveAfter(UUID cursorTenant, UUID cursorWorkspace, int limit) {
                return cursorTenant == null ? List.of(scope) : List.of();
            }
        };
        JsonMapper mapper = JsonMapper.builder().build();
        new CatalogPersistenceBootstrap(jdbc, new CatalogPersistenceSeedLoader(mapper), directory).importDeterministicSeed();
        new CatalogSkuPersistenceBootstrap(jdbc, new CatalogFamilySkuMappingLoader(mapper),
                new CatalogVariantMappingLoader(mapper), directory).reconcile();
    }

    private static void copyTables(PostgreSQLContainer source, PostgreSQLContainer target, List<String> tables) throws SQLException {
        try (Connection sourceConnection = adminConnection(source); Connection targetConnection = adminConnection(target)) {
            targetConnection.setAutoCommit(false);
            try {
                for (String table : tables) copyTable(sourceConnection, targetConnection, table);
                targetConnection.commit();
            } catch (SQLException exception) {
                targetConnection.rollback();
                throw exception;
            }
        }
    }

    private static void copyTable(Connection source, Connection target, String table) throws SQLException {
        List<String> columns = columnNames(source, table);
        List<String> keys = primaryKeyColumns(source, table);
        String columnList = columns.stream().map(TenantBusinessDatabaseBaselineIT::quote).collect(Collectors.joining(", "));
        String orderBy = keys.stream().map(TenantBusinessDatabaseBaselineIT::quote).collect(Collectors.joining(", "));
        String sql = "SELECT " + columnList + " FROM " + quoteQualified(table) + " ORDER BY " + orderBy;
        String insert = "INSERT INTO " + quoteQualified(table) + " (" + columnList + ") VALUES ("
                + columns.stream().map(ignored -> "?").collect(Collectors.joining(", ")) + ")";
        try (Statement select = source.createStatement(); ResultSet rows = select.executeQuery(sql);
             PreparedStatement insertStatement = target.prepareStatement(insert)) {
            while (rows.next()) {
                for (int index = 1; index <= columns.size(); index++) insertStatement.setObject(index, rows.getObject(index));
                insertStatement.addBatch();
            }
            insertStatement.executeBatch();
        }
    }

    private static List<String> columnNames(Connection connection, String table) throws SQLException {
        String[] parts = table.split("\\.", 2);
        List<String> names = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getColumns(null, parts[0], parts[1], null)) {
            while (result.next()) names.add(result.getString("COLUMN_NAME"));
        }
        return names;
    }

    private static List<String> topologicalTables(PostgreSQLContainer tenant, List<String> tables) throws SQLException {
        Set<String> selected = new LinkedHashSet<>(tables);
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (String table : selected) dependencies.put(table, new LinkedHashSet<>());
        try (Connection connection = adminConnection(tenant); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT source_ns.nspname || '.' || source_table.relname,
                            target_ns.nspname || '.' || target_table.relname
                     FROM pg_constraint c JOIN pg_class source_table ON source_table.oid=c.conrelid
                     JOIN pg_namespace source_ns ON source_ns.oid=source_table.relnamespace
                     JOIN pg_class target_table ON target_table.oid=c.confrelid
                     JOIN pg_namespace target_ns ON target_ns.oid=target_table.relnamespace
                     WHERE c.contype='f'
                     """)) {
            while (result.next()) {
                String source = result.getString(1), target = result.getString(2);
                if (selected.contains(source) && selected.contains(target) && !source.equals(target)) dependencies.get(source).add(target);
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> remaining = new TreeSet<>(selected);
        while (!remaining.isEmpty()) {
            List<String> ready = remaining.stream().filter(table -> dependencies.get(table).stream().noneMatch(remaining::contains))
                    .toList();
            if (ready.isEmpty()) throw new IllegalStateException("Catalog fixture dependency cycle in selected BC03 table set: " + remaining);
            order.addAll(ready); remaining.removeAll(ready);
        }
        return order;
    }

    private static void assertCatalogExtraction(PostgreSQLContainer source, PostgreSQLContainer tenantA,
            PostgreSQLContainer tenantB, List<String> tables, UUID tenantId, UUID workspaceId) throws SQLException {
        Map<String, String> sourceChecksums = tableChecksums(source, tables);
        Map<String, String> targetChecksums = tableChecksums(tenantA, tables);
        assertThat(targetChecksums).isEqualTo(sourceChecksums);
        try (Connection sourceConnection = adminConnection(source);
             Connection targetAConnection = adminConnection(tenantA);
             Connection targetBConnection = adminConnection(tenantB)) {
            for (String table : tables) {
                assertThat(rowCount(targetAConnection, table)).as("preserved row count for %s", table)
                        .isEqualTo(rowCount(sourceConnection, table));
                assertThat(rowCount(targetBConnection, table)).as("new Tenant remains unseeded for %s", table).isZero();
            }
        }
        assertThat(scalarLong(source, "SELECT count(*) FROM catalog_management.product WHERE tenant_id='" + tenantId + "' AND workspace_id='" + workspaceId + "'")).isEqualTo(102);
        assertThat(scalarLong(tenantA, "SELECT count(*) FROM catalog_management.product WHERE tenant_id='" + tenantId + "' AND workspace_id='" + workspaceId + "'")).isEqualTo(102);
        assertThat(scalarLong(source, "SELECT count(*) FROM catalog_management.sellable_sku WHERE tenant_id='" + tenantId + "' AND workspace_id='" + workspaceId + "'")).isEqualTo(102);
        assertThat(scalarLong(tenantA, "SELECT count(*) FROM catalog_management.sellable_sku WHERE tenant_id='" + tenantId + "' AND workspace_id='" + workspaceId + "'")).isEqualTo(102);
        assertThat(scalarLong(tenantA, "SELECT count(*) FROM catalog_management.sellable_sku WHERE tenant_id='" + tenantId + "' AND visible")).isEqualTo(50);
        assertThat(scalarLong(tenantA, "SELECT count(*) FROM catalog_management.sellable_sku WHERE tenant_id='" + tenantId + "' AND NOT visible")).isEqualTo(52);
        assertThat(scalarLong(tenantA, """
                SELECT count(*) FROM catalog_management.sellable_sku sku
                WHERE sku.tenant_id='%s' AND sku.workspace_id='%s'
                  AND (sku.id <> sku.legacy_product_id OR NOT EXISTS (
                      SELECT 1 FROM catalog_management.product p WHERE p.id=sku.legacy_product_id
                        AND p.tenant_id=sku.tenant_id AND p.workspace_id=sku.workspace_id))
                """.formatted(tenantId, workspaceId))).isZero();
    }

    private static Map<String, String> tableChecksums(PostgreSQLContainer container, List<String> tables) throws SQLException {
        Map<String, String> checksums = new LinkedHashMap<>();
        try (Connection connection = adminConnection(container)) {
            for (String table : tables) {
                List<String> keys = primaryKeyColumns(connection, table);
                String order = keys.stream().map(TenantBusinessDatabaseBaselineIT::quote).collect(Collectors.joining(", "));
                StringBuilder canonical = new StringBuilder();
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                        "SELECT to_jsonb(row_data)::text FROM " + quoteQualified(table) + " row_data ORDER BY " + order)) {
                    while (result.next()) {
                        String row = result.getString(1);
                        canonical.append(row.length()).append(':').append(row).append('\n');
                    }
                }
                checksums.put(table, sha256(canonical.toString()));
            }
        }
        return checksums;
    }

    private static long rowCount(PostgreSQLContainer container, String table) throws SQLException {
        try (Connection connection = adminConnection(container)) { return rowCount(connection, table); }
    }

    private static long scalarLong(PostgreSQLContainer container, String query) throws SQLException {
        try (Connection connection = adminConnection(container); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            result.next(); return result.getLong(1);
        }
    }

    private static boolean hasTablePrivilege(Connection connection, String table, String privilege) throws SQLException {
        return hasTablePrivilege(connection, TenantBusinessDatabaseBaselineGenerator.RUNTIME_ROLE, table, privilege);
    }

    private static boolean hasTablePrivilege(Connection connection, String role, String table, String privilege) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
            statement.setString(1, role); statement.setString(2, table); statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static Connection adminConnection(PostgreSQLContainer container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static DataSource adminDataSource(PostgreSQLContainer container) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(container.getJdbcUrl()); dataSource.setUsername(container.getUsername()); dataSource.setPassword(container.getPassword());
        return dataSource;
    }

    private static PostgreSQLContainer container(String databaseName) {
        return new PostgreSQLContainer("postgres:18.4-alpine")
                .withDatabaseName(databaseName).withUsername("nexa_admin").withPassword("bootstrap-admin-test-password");
    }

    private static String quoteQualified(String name) {
        String[] parts = name.split("\\.", 2);
        return quote(parts[0]) + "." + quote(parts[1]);
    }

    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static final Set<String> GLOBAL_REFERENCE_TABLES = Set.of("reference_data.department", "reference_data.district",
            "reference_data.province", "reference_data.road_type");
}
