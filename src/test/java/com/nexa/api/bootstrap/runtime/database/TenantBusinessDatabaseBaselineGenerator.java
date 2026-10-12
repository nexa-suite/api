package com.nexa.api.bootstrap.runtime.database;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Builds the additive V3 baseline from a fresh database at the published central V146 state. */
final class TenantBusinessDatabaseBaselineGenerator {
    static final String MANIFEST_PATH = "src/main/resources/db/tenant-migration/tenant-business-baseline-v3.tsv";
    static final String CANONICAL_OWNERSHIP_PATH = "docs/architecture/canonical-sql-ownership.tsv";
    static final String SOURCE_MIGRATIONS_PATH = "src/main/resources/db/migration";
    static final String OUTPUT_PATH = "src/main/resources/db/tenant-migration/V3__tenant_business_schema_baseline.sql";
    static final String RUNTIME_ROLE = "nexa_runtime";
    static final String MIGRATOR_ROLE = "nexa_migrator";

    private static final Pattern FOREIGN_KEY_DUMP_STATEMENT = Pattern.compile(
            "(?m)^ALTER TABLE ONLY\\s+[^;]*?FOREIGN KEY[^;]*;\\s*");
    private static final Pattern SEQUENCE_DUMP_STATEMENT = Pattern.compile(
            "(?m)^(?:CREATE SEQUENCE|ALTER SEQUENCE)\\s+[^;]*;\\s*");
    private static final Pattern FORCE_RLS_DUMP_STATEMENT = Pattern.compile(
            "(?m)^ALTER TABLE(?: ONLY)?\\s+[^;]+? FORCE ROW LEVEL SECURITY;\\s*");
    private static final Set<String> ACTOR_REFERENCE_TARGETS = Set.of(
            "iam.user_account", "tenant_management.workspace_membership");
    private static final List<String> TABLE_PRIVILEGES = List.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN");
    private static final List<String> SEQUENCE_PRIVILEGES = List.of("USAGE", "SELECT", "UPDATE");
    private static final Set<String> GLOBAL_REFERENCE_TABLES = Set.of(
            "reference_data.department", "reference_data.district", "reference_data.province", "reference_data.road_type");
    private static final List<String> GLOBAL_REFERENCE_LOAD_ORDER = List.of(
            "reference_data.department", "reference_data.province", "reference_data.district", "reference_data.road_type");
    private static final Set<String> TENANT_LOCAL_SHARED_TECHNICAL_TABLES = Set.of(
            "catalog_management.command_idempotency",
            "integration.change_event", "integration.inbox_event", "integration.outbox_event",
            "logistics.command_idempotency", "logistics.delivery_command_idempotency", "logistics.fulfillment_command_idempotency",
            "notifications.push_subscription_command_idempotency",
            "payments.reconciliation_refund_idempotency",
            "sales.idempotency_record", "sales.idempotency_response", "sales.manual_order_idempotency",
            "sales.manual_sales_order_draft_idempotency", "sales.purchase_request_draft_idempotency",
            "warehouse.command_idempotency", "warehouse.physical_allocation_command_idempotency");

    private TenantBusinessDatabaseBaselineGenerator() { }

    static List<Table> readManifest(Path repositoryRoot) throws IOException {
        Path manifestPath = repositoryRoot.resolve(MANIFEST_PATH);
        Path ownershipPath = repositoryRoot.resolve(CANONICAL_OWNERSHIP_PATH);
        Map<String, String> manifest = readOwnerTableTsv(manifestPath);
        Map<String, String> canonical = readOwnerTableTsv(ownershipPath);
        Map<String, String> expected = new TreeMap<>();
        canonical.forEach((table, owner) -> {
            if (owner.equals("GLOBAL_REFERENCE") || owner.matches("BC(0[2-9]|1[01])")) expected.put(table, owner);
        });
        for (String table : TENANT_LOCAL_SHARED_TECHNICAL_TABLES) {
            if (!"SHARED_TECHNICAL".equals(canonical.get(table))) {
                throw new IllegalStateException("Approved tenant-local technical table is not canonically classified: " + table);
            }
            expected.put(table, "SHARED_TECHNICAL");
        }
        if (!manifest.equals(expected)) {
            throw new IllegalStateException("V3 selector must equal all canonical BC02-BC11, shared-technical, and global-reference rows; expected "
                    + expected.size() + " entries but found " + manifest.size());
        }
        if (!manifest.keySet().containsAll(GLOBAL_REFERENCE_TABLES)) {
            throw new IllegalStateException("V3 selector is missing a global-reference projection");
        }
        return manifest.entrySet().stream().map(entry -> new Table(entry.getKey(), entry.getValue())).toList();
    }

    static String generate(PostgreSQLContainer central, Path repositoryRoot) throws Exception {
        List<Table> tables = readManifest(repositoryRoot);
        try (Connection connection = DriverManager.getConnection(
                central.getJdbcUrl(), central.getUsername(), central.getPassword())) {
            int centralVersion = successfulCentralVersion(connection);
            if (centralVersion != 146) {
                throw new IllegalStateException("V3 baseline requires published central Flyway V146; found V" + centralVersion);
            }
            String ownershipDigest = sha256(Files.readAllBytes(repositoryRoot.resolve(CANONICAL_OWNERSHIP_PATH)));
            String selectorDigest = sha256(Files.readAllBytes(repositoryRoot.resolve(MANIFEST_PATH)));
            String migrationDigest = migrationCorpusDigest(repositoryRoot.resolve(SOURCE_MIGRATIONS_PATH));

            String tableDump = dumpTables(central, tables);
            SequenceProjection sequences = sequenceDefinitions(connection, tables);
            String functions = functionDefinitions(connection, tables);
            String functionGrants = functionGrants(connection, tables);
            String foreignKeys = foreignKeyDefinitions(connection, tables);
            String references = referenceData(connection);
            String grants = runtimeGrants(connection, tables);
            String forcedRls = forceRowLevelSecurityStatements(tableDump);
            String schemas = tables.stream().map(Table::schema).collect(Collectors.toCollection(TreeSet::new))
                    .stream().map(TenantBusinessDatabaseBaselineGenerator::createSchema)
                    .collect(Collectors.joining("\n"));
            String cleanDump = stripDumpPreambleAndForeignKeys(tableDump);

            return """
                    -- Additive Tenant business schema projection from the published central Flyway V146 state.
                    -- Generated by TenantBusinessDatabaseBaselineGenerator; canonical ownership and source digests are verified by IT.
                    -- The migration creates schema only plus immutable GLOBAL_REFERENCE projections; it does not seed Tenant business data.
                    -- Central IAM, users, Tenant/Workspace authority, roles, memberships and V1-V146 history remain central and unchanged.
                    -- Source corpus SHA-256: %s
                    -- Canonical ownership SHA-256: %s
                    -- V3 selector SHA-256: %s
                    -- V3 selected tables: %d
                    SET check_function_bodies = false;
                    SET row_security = off;

                    %s

                    CREATE EXTENSION IF NOT EXISTS btree_gist;

                    -- Owned sequences must exist before table defaults that call nextval.
                    %s

                    -- Retain selected business-schema helper functions used by table triggers and workers.
                    %s

                    -- Current V146 table, index, check, policy, trigger and RLS definitions from the selected relations.
                    %s

                    %s

                    -- Tenant/Workspace references are local; actor UUID columns remain but central identity FKs are omitted.
                    %s

                    -- FORCE RLS only after local Tenant/Workspace foreign keys are validated by PostgreSQL.
                    %s

                    -- GLOBAL_REFERENCE rows are immutable local projections. loaded_at is local migration metadata.
                    %s

                    -- Runtime grants mirror the source role for business tables; global references remain SELECT-only.
                    %s

                    -- Preserve explicitly restricted maintenance-function access.
                    %s
                    """.formatted(migrationDigest, ownershipDigest, selectorDigest, tables.size(), schemas,
                    sequences.createStatements(), functions, cleanDump, sequences.ownershipStatements(),
                    foreignKeys, forcedRls, references, grants, functionGrants);
        }
    }

    private static Map<String, String> readOwnerTableTsv(Path path) throws IOException {
        Map<String, String> rows = new TreeMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length >= 2
                    && ((fields[0].equals("table") && fields[1].equals("owner"))
                    || (fields[0].equals("owner") && fields[1].equals("table")))) continue;
            if (fields.length < 2) throw new IllegalStateException("Invalid owner/table TSV row at " + path + ":" + (index + 1));
            String table;
            String owner;
            if (fields[0].contains(".")) {
                table = fields[0];
                owner = fields[1];
            } else {
                owner = fields[0];
                table = fields[1];
            }
            if (rows.put(table, owner) != null) throw new IllegalStateException("Duplicate table in " + path + ": " + table);
        }
        return rows;
    }

    private static int successfulCentralVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT max(version::integer)
                     FROM flyway_schema_history
                     WHERE success = true AND version IS NOT NULL
                     """)) {
            if (!result.next() || result.getObject(1) == null) throw new IllegalStateException("Central Flyway history is absent");
            return result.getInt(1);
        }
    }

    private static String dumpTables(PostgreSQLContainer central, List<Table> tables) throws Exception {
        List<String> command = new ArrayList<>(List.of("pg_dump", "--username", central.getUsername(), "--dbname",
                central.getDatabaseName(), "--schema-only", "--no-owner", "--no-acl", "--format=plain"));
        for (Table table : tables) command.add("--table=" + table.name());
        var result = central.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("pg_dump failed while projecting selected Tenant business tables: " + result.getStderr());
        }
        return result.getStdout();
    }

    private static String stripDumpPreambleAndForeignKeys(String dump) {
        String result = FOREIGN_KEY_DUMP_STATEMENT.matcher(dump).replaceAll("");
        result = SEQUENCE_DUMP_STATEMENT.matcher(result).replaceAll("");
        result = FORCE_RLS_DUMP_STATEMENT.matcher(result).replaceAll("");
        return Arrays.stream(result.split("\\R", -1))
                .filter(line -> !line.startsWith("\\restrict") && !line.startsWith("\\unrestrict"))
                .filter(line -> !line.startsWith("-- PostgreSQL database dump"))
                .filter(line -> !line.startsWith("-- Dumped from database version"))
                .filter(line -> !line.startsWith("-- Dumped by pg_dump version"))
                .filter(line -> !line.strip().equals("SELECT pg_catalog.set_config('search_path', '', false);"))
                .filter(line -> !line.startsWith("CREATE SCHEMA ") && !line.startsWith("COMMENT ON SCHEMA "))
                .filter(line -> !line.startsWith("ALTER SCHEMA "))
                .collect(Collectors.joining("\n")).strip();
    }

    private static String forceRowLevelSecurityStatements(String dump) {
        Matcher matcher = FORCE_RLS_DUMP_STATEMENT.matcher(dump);
        List<String> statements = new ArrayList<>();
        while (matcher.find()) statements.add(matcher.group().strip());
        return statements.isEmpty() ? "-- No selected table forces row-level security." : String.join("\n", statements);
    }

    private static String functionDefinitions(Connection connection, List<Table> tables) throws SQLException {
        Set<String> schemas = tables.stream().map(Table::schema)
                .filter(schema -> !schema.equals("reference_data") && !schema.equals("tenant_management"))
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> definitions = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT pg_get_functiondef(p.oid)
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = ANY (?) AND p.prokind = 'f'
                ORDER BY n.nspname, p.proname, pg_get_function_identity_arguments(p.oid)
                """)) {
            statement.setArray(1, connection.createArrayOf("text", schemas.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) definitions.add(result.getString(1));
            }
        }
        if (definitions.isEmpty()) return "-- No selected business functions are defined.";
        return definitions.stream().map(definition -> definition.stripTrailing() + ";")
                .collect(Collectors.joining("\n\n"));
    }

    private static SequenceProjection sequenceDefinitions(Connection connection, List<Table> tables) throws SQLException {
        Set<String> selected = tables.stream().map(Table::name).collect(Collectors.toSet());
        List<String> createStatements = new ArrayList<>();
        List<String> ownershipStatements = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
                SELECT sequence_ns.nspname, sequence_row.relname, format_type(sequence_definition.seqtypid, NULL),
                       sequence_definition.seqstart, sequence_definition.seqincrement,
                       sequence_definition.seqmin, sequence_definition.seqmax, sequence_definition.seqcache,
                       sequence_definition.seqcycle, table_ns.nspname, table_row.relname, attribute.attname
                FROM pg_class sequence_row
                JOIN pg_namespace sequence_ns ON sequence_ns.oid=sequence_row.relnamespace
                JOIN pg_sequence sequence_definition ON sequence_definition.seqrelid=sequence_row.oid
                JOIN pg_depend dependency ON dependency.objid=sequence_row.oid AND dependency.classid='pg_class'::regclass
                JOIN pg_class table_row ON table_row.oid=dependency.refobjid
                JOIN pg_namespace table_ns ON table_ns.oid=table_row.relnamespace
                JOIN pg_attribute attribute ON attribute.attrelid=table_row.oid AND attribute.attnum=dependency.refobjsubid
                WHERE sequence_row.relkind='S' AND dependency.refclassid='pg_class'::regclass
                  AND dependency.deptype IN ('a','i')
                ORDER BY sequence_ns.nspname, sequence_row.relname
                """)) {
            while (result.next()) {
                String sequence = result.getString(1) + "." + result.getString(2);
                String table = result.getString(10) + "." + result.getString(11);
                if (!selected.contains(table)) continue;
                createStatements.add("CREATE SEQUENCE " + quoteQualified(sequence) + " AS " + result.getString(3)
                        + " INCREMENT BY " + result.getLong(5) + " MINVALUE " + result.getLong(6)
                        + " MAXVALUE " + result.getLong(7) + " START WITH " + result.getLong(4)
                        + " CACHE " + result.getLong(8) + (result.getBoolean(9) ? " CYCLE" : " NO CYCLE") + ";");
                ownershipStatements.add("ALTER SEQUENCE " + quoteQualified(sequence) + " OWNED BY "
                        + quoteQualified(table) + "." + quote(result.getString(12)) + ";");
            }
        }
        if (createStatements.isEmpty()) {
            return new SequenceProjection("-- No selected business table owns a sequence.", "-- No sequence ownership clauses are required.");
        }
        return new SequenceProjection(String.join("\n", createStatements), String.join("\n", ownershipStatements));
    }

    private static String functionGrants(Connection connection, List<Table> tables) throws SQLException {
        Set<String> schemas = tables.stream().map(Table::schema)
                .filter(schema -> !schema.equals("reference_data") && !schema.equals("tenant_management"))
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> statements = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT n.nspname, p.proname, pg_get_function_identity_arguments(p.oid),
                       EXISTS (SELECT 1 FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
                               WHERE acl.grantee = 0 AND acl.privilege_type = 'EXECUTE'),
                       EXISTS (SELECT 1 FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
                               WHERE acl.grantee = 'nexa_runtime'::regrole AND acl.privilege_type = 'EXECUTE'),
                       EXISTS (SELECT 1 FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
                               JOIN pg_roles grantee ON grantee.oid = acl.grantee
                               WHERE grantee.rolname NOT IN (pg_get_userbyid(p.proowner), 'nexa_runtime')
                                 AND acl.grantee <> 0 AND acl.privilege_type = 'EXECUTE')
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE p.prokind='f' AND n.nspname = ANY (?)
                ORDER BY n.nspname, p.proname, pg_get_function_identity_arguments(p.oid)
                """)) {
            statement.setArray(1, connection.createArrayOf("text", schemas.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String schema = result.getString(1), name = result.getString(2), args = result.getString(3);
                    boolean publicExecute = result.getBoolean(4), runtimeExecute = result.getBoolean(5);
                    if (result.getBoolean(6)) {
                        throw new IllegalStateException("Selected function has an unapproved external ACL grantee: " + schema + "." + name);
                    }
                    String function = quote(schema) + "." + quote(name) + "(" + args + ")";
                    if (!publicExecute) statements.add("REVOKE ALL ON FUNCTION " + function + " FROM PUBLIC;");
                    if (runtimeExecute) statements.add("GRANT EXECUTE ON FUNCTION " + function + " TO " + quote(RUNTIME_ROLE) + ";");
                }
            }
        }
        return statements.isEmpty() ? "-- No additional function ACLs are required by the source role." : String.join("\n", statements);
    }

    private static String foreignKeyDefinitions(Connection connection, List<Table> tables) throws SQLException {
        Set<String> selected = tables.stream().map(Table::name).collect(Collectors.toSet());
        List<ForeignKey> foreignKeys = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT source_ns.nspname, source_table.relname, constraint_row.conname,
                       source_columns.column_names, target_ns.nspname, target_table.relname,
                       target_columns.column_names, pg_get_constraintdef(constraint_row.oid, true)
                FROM pg_constraint constraint_row
                JOIN pg_class source_table ON source_table.oid = constraint_row.conrelid
                JOIN pg_namespace source_ns ON source_ns.oid = source_table.relnamespace
                JOIN pg_class target_table ON target_table.oid = constraint_row.confrelid
                JOIN pg_namespace target_ns ON target_ns.oid = target_table.relnamespace
                CROSS JOIN LATERAL (
                    SELECT array_agg(attribute.attname ORDER BY keys.ordinality) AS column_names
                    FROM unnest(constraint_row.conkey) WITH ORDINALITY AS keys(attnum, ordinality)
                    JOIN pg_attribute attribute ON attribute.attrelid = source_table.oid AND attribute.attnum = keys.attnum
                ) source_columns
                CROSS JOIN LATERAL (
                    SELECT array_agg(attribute.attname ORDER BY keys.ordinality) AS column_names
                    FROM unnest(constraint_row.confkey) WITH ORDINALITY AS keys(attnum, ordinality)
                    JOIN pg_attribute attribute ON attribute.attrelid = target_table.oid AND attribute.attnum = keys.attnum
                ) target_columns
                WHERE constraint_row.contype = 'f'
                  AND source_ns.nspname <> 'pg_catalog'
                ORDER BY source_ns.nspname, source_table.relname, constraint_row.conname
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String source = result.getString(1) + "." + result.getString(2);
                    if (!selected.contains(source)) continue;
                    foreignKeys.add(new ForeignKey(source, result.getString(3),
                            strings(result.getArray(4)), result.getString(5) + "." + result.getString(6),
                            strings(result.getArray(7)), result.getString(8)));
                }
            }
        }

        List<String> statements = new ArrayList<>();
        for (ForeignKey foreignKey : foreignKeys) {
            String target = foreignKey.target();
            List<String> sourceColumns = foreignKey.sourceColumns();
            List<String> targetColumns = foreignKey.targetColumns();
            String definition = foreignKey.definition();
            if (selected.contains(target)) {
                // Keep business-to-business and business-to-global-reference foreign keys unchanged.
            } else if (target.equals("tenant_management.tenant")) {
                if (!sourceColumns.equals(List.of("tenant_id")) || !targetColumns.equals(List.of("id"))) {
                    throw new IllegalStateException("Unexpected Tenant identity FK shape: " + foreignKey);
                }
                definition = replaceReference(definition, target, List.of("id"),
                        "nexa_platform.tenant_business_database_identity", List.of("tenant_id"));
            } else if (target.equals("tenant_management.workspace")) {
                if (targetColumns.equals(List.of("tenant_id", "id"))) {
                    if (!sourceColumns.equals(List.of("tenant_id", "workspace_id"))) {
                        throw new IllegalStateException("Unexpected composite Workspace FK shape: " + foreignKey);
                    }
                } else if (targetColumns.equals(List.of("id")) && sourceColumns.equals(List.of("workspace_id"))) {
                    if (hasColumn(connection, foreignKey.source(), "tenant_id")) {
                        sourceColumns = List.of("tenant_id", "workspace_id");
                        definition = replaceForeignKeySourceColumns(definition, foreignKey.sourceColumns(), sourceColumns);
                        definition = replaceReference(definition, target, targetColumns,
                                "nexa_platform.tenant_workspace_scope_anchor", sourceColumns);
                        statements.add("ALTER TABLE " + quoteQualified(foreignKey.source()) + " ADD CONSTRAINT "
                                + quote(foreignKey.name()) + " " + definition + ";");
                        continue;
                    }
                    // Legacy BC10 notification preferences store only the globally unique Workspace UUID.
                    // V2 anchors enforce that this UUID belongs to the provisioned Tenant database.
                    definition = replaceReference(definition, target, targetColumns,
                            "nexa_platform.tenant_workspace_scope_anchor", List.of("workspace_id"));
                    statements.add("ALTER TABLE " + quoteQualified(foreignKey.source()) + " ADD CONSTRAINT "
                            + quote(foreignKey.name()) + " " + definition + ";");
                    continue;
                } else {
                    throw new IllegalStateException("Unexpected Workspace FK shape: " + foreignKey);
                }
                definition = replaceReference(definition, target, targetColumns,
                        "nexa_platform.tenant_workspace_scope_anchor", List.of("tenant_id", "workspace_id"));
            } else if (ACTOR_REFERENCE_TARGETS.contains(target)) {
                continue;
            } else {
                throw new IllegalStateException("Selected business table has an unclassified external FK: " + foreignKey);
            }
            statements.add("ALTER TABLE " + quoteQualified(foreignKey.source()) + " ADD CONSTRAINT "
                    + quote(foreignKey.name()) + " " + definition + ";");
        }
        return statements.isEmpty() ? "-- No foreign keys found in the selected schema." : String.join("\n", statements);
    }

    private static boolean hasColumn(Connection connection, String qualifiedTable, String column) throws SQLException {
        String[] parts = qualifiedTable.split("\\.", 2);
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_schema = ? AND table_name = ? AND column_name = ?)
                """)) {
            statement.setString(1, parts[0]);
            statement.setString(2, parts[1]);
            statement.setString(3, column);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static String replaceReference(String definition, String oldTable, List<String> oldColumns,
            String newTable, List<String> newColumns) {
        String oldReference = "REFERENCES " + oldTable + " (" + oldColumns.stream().map(TenantBusinessDatabaseBaselineGenerator::quote)
                .collect(Collectors.joining(", ")) + ")";
        // pg_get_constraintdef does not quote lowercase identifiers by default.
        oldReference = oldReference.replace("\"", "");
        String newReference = "REFERENCES " + quoteQualified(newTable) + " ("
                + newColumns.stream().map(TenantBusinessDatabaseBaselineGenerator::quote).collect(Collectors.joining(", ")) + ")";
        int index = definition.indexOf(oldReference);
        if (index < 0) {
            String unquoted = "REFERENCES " + oldTable + "(" + oldColumns.stream().collect(Collectors.joining(", ")) + ")";
            index = definition.indexOf(unquoted);
            if (index < 0) throw new IllegalStateException("Cannot rewrite external reference in constraint: " + definition);
            return definition.substring(0, index) + newReference + definition.substring(index + unquoted.length());
        }
        return definition.substring(0, index) + newReference + definition.substring(index + oldReference.length());
    }

    private static String replaceForeignKeySourceColumns(String definition, List<String> oldColumns, List<String> newColumns) {
        String oldPrefix = "FOREIGN KEY (" + String.join(", ", oldColumns) + ")";
        String newPrefix = "FOREIGN KEY (" + newColumns.stream().map(TenantBusinessDatabaseBaselineGenerator::quote)
                .collect(Collectors.joining(", ")) + ")";
        int index = definition.indexOf(oldPrefix);
        if (index < 0) throw new IllegalStateException("Cannot upgrade unary Workspace FK columns: " + definition);
        return definition.substring(0, index) + newPrefix + definition.substring(index + oldPrefix.length());
    }

    private static List<String> strings(java.sql.Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(Object::toString).toList();
    }

    private static String referenceData(Connection connection) throws SQLException {
        StringBuilder sql = new StringBuilder();
        for (String table : GLOBAL_REFERENCE_LOAD_ORDER) {
            List<Column> columns = columns(connection, table);
            List<Column> values = columns.stream().filter(column -> !column.name().equals("loaded_at")).toList();
            if (columns.stream().noneMatch(column -> column.name().equals("loaded_at")
                    && column.dataType().equals("timestamp with time zone"))) {
                throw new IllegalStateException("Unexpected GLOBAL_REFERENCE projection columns for " + table);
            }
            if (values.stream().anyMatch(column -> !Set.of("character varying", "character", "text").contains(column.dataType()))) {
                throw new IllegalStateException("GLOBAL_REFERENCE projection contains an unreviewed column type in " + table);
            }
            List<String> keyColumns = primaryKeyColumns(connection, table);
            String selectedColumns = values.stream().map(column -> quote(column.name())).collect(Collectors.joining(", "));
            List<List<String>> rows = referenceRows(connection, table, values, keyColumns);
            sql.append("-- ").append(table).append(" rows: ").append(rows.size())
                    .append("; logical-data SHA-256: ").append(referenceRowsDigest(rows)).append('\n');
            for (List<String> row : rows) {
                String literals = row.stream().map(TenantBusinessDatabaseBaselineGenerator::stringLiteral)
                        .collect(Collectors.joining(", "));
                sql.append("INSERT INTO ").append(quoteQualified(table)).append(" (").append(selectedColumns)
                        .append(", loaded_at) VALUES (").append(literals).append(", current_timestamp);\n");
            }
        }
        return sql.toString().strip();
    }

    private static List<List<String>> referenceRows(Connection connection, String table, List<Column> columns,
            List<String> keyColumns) throws SQLException {
        String selectedColumns = columns.stream().map(column -> quote(column.name())).collect(Collectors.joining(", "));
        String order = keyColumns.stream().map(TenantBusinessDatabaseBaselineGenerator::quote).collect(Collectors.joining(", "));
        List<List<String>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + selectedColumns + " FROM " + quoteQualified(table) + " ORDER BY " + order)) {
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int index = 1; index <= columns.size(); index++) row.add(result.getString(index));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String referenceRowsDigest(List<List<String>> rows) {
        StringBuilder canonical = new StringBuilder();
        for (List<String> row : rows) {
            for (String value : row) {
                canonical.append(value == null ? "-1:" : value.length() + ":" + value).append('|');
            }
            canonical.append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Column> columns(Connection connection, String table) throws SQLException {
        String[] parts = table.split("\\.", 2);
        List<Column> columns = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY ordinal_position
                """)) {
            statement.setString(1, parts[0]);
            statement.setString(2, parts[1]);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) columns.add(new Column(result.getString(1), result.getString(2)));
            }
        }
        if (columns.isEmpty()) throw new IllegalStateException("Missing reference table " + table);
        return columns;
    }

    private static List<String> primaryKeyColumns(Connection connection, String table) throws SQLException {
        String[] parts = table.split("\\.", 2);
        try (var statement = connection.prepareStatement("""
                SELECT array_agg(attribute.attname ORDER BY key_column.ordinality)
                FROM pg_constraint constraint_row
                JOIN pg_class relation ON relation.oid = constraint_row.conrelid
                JOIN pg_namespace namespace_row ON namespace_row.oid = relation.relnamespace
                CROSS JOIN LATERAL unnest(constraint_row.conkey) WITH ORDINALITY AS key_column(attnum, ordinality)
                JOIN pg_attribute attribute ON attribute.attrelid = relation.oid AND attribute.attnum = key_column.attnum
                WHERE constraint_row.contype = 'p' AND namespace_row.nspname = ? AND relation.relname = ?
                """)) {
            statement.setString(1, parts[0]);
            statement.setString(2, parts[1]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                List<String> keys = strings(result.getArray(1));
                if (keys.isEmpty()) throw new IllegalStateException("GLOBAL_REFERENCE table has no primary key: " + table);
                return keys;
            }
        }
    }

    private static String runtimeGrants(Connection connection, List<Table> tables) throws SQLException {
        List<String> statements = new ArrayList<>();
        Set<String> schemas = tables.stream().map(Table::schema).collect(Collectors.toCollection(TreeSet::new));
        for (String schema : schemas) {
            if (hasSchemaPrivilege(connection, schema, "USAGE")) {
                statements.add("GRANT USAGE ON SCHEMA " + quote(schema) + " TO " + quote(RUNTIME_ROLE) + ";");
            }
        }
        for (Table table : tables) {
            if (GLOBAL_REFERENCE_TABLES.contains(table.name())) {
                statements.add("REVOKE ALL PRIVILEGES ON TABLE " + quoteQualified(table.name()) + " FROM PUBLIC, " + quote(RUNTIME_ROLE) + ";");
                statements.add("GRANT SELECT ON TABLE " + quoteQualified(table.name()) + " TO " + quote(RUNTIME_ROLE) + ";");
                continue;
            }
            List<String> granted = new ArrayList<>();
            for (String privilege : TABLE_PRIVILEGES) {
                if (hasTablePrivilege(connection, table.name(), privilege)) granted.add(privilege);
            }
            if (!granted.isEmpty()) {
                statements.add("GRANT " + String.join(", ", granted) + " ON TABLE " + quoteQualified(table.name())
                        + " TO " + quote(RUNTIME_ROLE) + ";");
            }
        }
        for (String sequence : selectedSequences(connection, tables)) {
            List<String> granted = new ArrayList<>();
            for (String privilege : SEQUENCE_PRIVILEGES) {
                if (hasSequencePrivilege(connection, sequence, privilege)) granted.add(privilege);
            }
            if (!granted.isEmpty()) {
                statements.add("GRANT " + String.join(", ", granted) + " ON SEQUENCE " + quoteQualified(sequence)
                        + " TO " + quote(RUNTIME_ROLE) + ";");
            }
        }
        return statements.isEmpty() ? "-- No explicit runtime grants are required by the source role." : String.join("\n", statements);
    }

    private static boolean hasSchemaPrivilege(Connection connection, String schema, String privilege) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT has_schema_privilege(?, ?, ?)")) {
            statement.setString(1, RUNTIME_ROLE); statement.setString(2, schema); statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static boolean hasTablePrivilege(Connection connection, String table, String privilege) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
            statement.setString(1, RUNTIME_ROLE); statement.setString(2, table); statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static boolean hasSequencePrivilege(Connection connection, String sequence, String privilege) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT has_sequence_privilege(?, ?, ?)")) {
            statement.setString(1, RUNTIME_ROLE); statement.setString(2, sequence); statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static List<String> selectedSequences(Connection connection, List<Table> tables) throws SQLException {
        Set<String> selected = tables.stream().map(Table::name).collect(Collectors.toSet());
        List<String> sequences = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
                SELECT DISTINCT sequence_ns.nspname || '.' || sequence_row.relname,
                       table_ns.nspname || '.' || table_row.relname
                FROM pg_class sequence_row
                JOIN pg_namespace sequence_ns ON sequence_ns.oid = sequence_row.relnamespace
                JOIN pg_depend dependency ON dependency.objid = sequence_row.oid AND dependency.classid = 'pg_class'::regclass
                JOIN pg_class table_row ON table_row.oid = dependency.refobjid
                JOIN pg_namespace table_ns ON table_ns.oid = table_row.relnamespace
                WHERE sequence_row.relkind = 'S' AND dependency.refclassid = 'pg_class'::regclass
                  AND dependency.deptype IN ('a', 'i')
                ORDER BY 1
                """)) {
            while (result.next()) if (selected.contains(result.getString(2))) sequences.add(result.getString(1));
        }
        return sequences;
    }

    private static String createSchema(String schema) { return "CREATE SCHEMA IF NOT EXISTS " + quote(schema) + ";"; }
    private static String quoteQualified(String qualified) {
        String[] parts = qualified.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Expected schema-qualified identifier: " + qualified);
        return quote(parts[0]) + "." + quote(parts[1]);
    }
    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    private static String stringLiteral(String value) { return value == null ? "NULL" : "'" + value.replace("'", "''") + "'"; }

    private static String migrationCorpusDigest(Path migrationDirectory) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(migrationDirectory)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(migrationDirectory.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(path));
                    digest.update((byte) 0xff);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    record Table(String name, String owner) {
        String schema() { return name.substring(0, name.indexOf('.')); }
    }
    private record SequenceProjection(String createStatements, String ownershipStatements) { }
    private record Column(String name, String dataType) { }
    private record ForeignKey(String source, String name, List<String> sourceColumns, String target,
                             List<String> targetColumns, String definition) { }
}
