package com.nexa.api.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceOwnershipTests {
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/nexa/api");
    private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");
    private static final Path TENANT_MIGRATION_ROOT = Path.of("src/main/resources/db/tenant-migration");
    private static final Path OWNERSHIP_FILE = Path.of("docs/architecture/canonical-sql-ownership.tsv");
    private static final Pattern SQL_TABLE = Pattern.compile(
            "\\b(?:from|join|into|update|delete\\s+from)\\s+(?:only\\s+)?"
                    + "\\\"?([a-z_][\\w$]*)\\\"?\\s*\\.\\s*\\\"?([a-z_][\\w$]*)\\\"?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATED_TABLE = Pattern.compile(
            "\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?"
                    + "\\\"?([a-z_][\\w$]*)\\\"?\\s*\\.\\s*\\\"?([a-z_][\\w$]*)\\\"?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ONLY_CONCATENATION = Pattern.compile("\\s*\\+\\s*");
    private static final List<ConsumerRoot> CONSUMERS = List.of(
            new ConsumerRoot("tenantaccessgovernance", "BC01"),
            new ConsumerRoot("customerbuyerrelationships", "BC02"),
            new ConsumerRoot("catalogcommercialpolicy", "BC03"),
            new ConsumerRoot("salescommitment", "BC04"),
            new ConsumerRoot("inventoryavailability", "BC05"),
            new ConsumerRoot("fulfillmentdelivery", "BC06"),
            new ConsumerRoot("creditreceivables", "BC07"),
            new ConsumerRoot("payments", "BC08"),
            new ConsumerRoot("businessdocuments", "BC09"),
            new ConsumerRoot("notifications", "BC10"),
            new ConsumerRoot("businesstraceability", "BC11"));

    @Test
    void migrationTablesHaveExplicitOwnershipEvidence() throws IOException {
        Map<String, Ownership> ownership = ownershipManifest();
        Set<String> createdTables = migrationTables();

        assertThat(ownership.keySet())
                .as("manifest must list every migration-created table, with no guessed tables")
                .containsExactlyInAnyOrderElementsOf(createdTables);
        assertThat(ownership.values()).allSatisfy(row -> {
            assertThat(row.owner()).isIn("BC01", "BC02", "BC03", "BC04", "BC05", "BC06", "BC07",
                    "BC08", "BC09", "BC10", "BC11", "GLOBAL_REFERENCE", "SHARED_TECHNICAL");
            assertThat(row.evidence()).contains(":", "CREATE TABLE");
        });
    }

    @Test
    void prescribedOwnershipExceptionsAreExplicit() throws IOException {
        Map<String, Ownership> ownership = ownershipManifest();
        assertOwners(ownership, "BC01", "iam.user_account", "tenant_management.workspace",
                "tenant_management.wallet_recharge_provider_route");
        assertOwners(ownership, "BC02", "sales.client_account", "sales.client_account_address",
                "sales.client_account_membership");
        assertOwners(ownership, "BC03", "catalog_management.sellable_sku");
        assertOwners(ownership, "BC04", "sales.sales_order");
        assertOwners(ownership, "BC05", "warehouse.inventory_lot");
        assertOwners(ownership, "BC06", "logistics.fulfillment");
        assertOwners(ownership, "BC07", "payments.credit_account", "payments.credit_reservation",
                "payments.receivable", "payments.receivable_allocation", "payments.receivable_application",
                "payments.financial_adjustment", "payments.financial_ledger_entry",
                "payments.refund_credit_obligation");
        assertOwners(ownership, "BC08", "payments.payment", "payments.buyer_wallet_account",
                "payments.buyer_wallet_ledger_entry", "payments.buyer_wallet_reservation",
                "payments.buyer_wallet_reservation_event", "payments.buyer_wallet_recharge",
                "payments.buyer_wallet_recharge_processed_event");
        assertOwners(ownership, "BC09", "business_documents.business_document");
        assertOwners(ownership, "BC10", "notifications.inbox_item", "tenant_management.notification_preference");
        assertOwners(ownership, "BC11", "audit.event");
        assertOwners(ownership, "SHARED_TECHNICAL",
                "catalog_management.command_idempotency", "logistics.command_idempotency",
                "logistics.delivery_command_idempotency", "logistics.fulfillment_command_idempotency",
                "notifications.push_subscription_command_idempotency",
                "payments.reconciliation_refund_idempotency", "sales.idempotency_record",
                "sales.idempotency_response", "sales.manual_order_idempotency",
                "sales.manual_sales_order_draft_idempotency", "sales.purchase_request_draft_idempotency",
                "tenant_management.organization_invitation_idempotency",
                "tenant_management.organization_registration_draft_idempotency",
                "tenant_management.workspace_creation_idempotency", "warehouse.command_idempotency",
                "warehouse.physical_allocation_command_idempotency", "integration.change_event",
                "integration.inbox_event", "integration.outbox_event");
        assertOwners(ownership, "GLOBAL_REFERENCE", "reference_data.department", "reference_data.province",
                "reference_data.district", "reference_data.road_type");
    }

    @Test
    void scannerFindsLegacyPlacementsSplitLiteralsTextBlocksAndUnknownTables() throws IOException {
        String source = """
                class Example {
                    String split = "select * " + "FROM catalog_management." + "sellable_sku";
                    String block = %s;
                    String local = "SELECT * FROM warehouse.inventory_lot";
                    String localWrite = "INSERT INTO warehouse.stock_movement(id) VALUES (?)";
                    String legacy = "DELETE FROM payments.receivable";
                    String unknown = "UPDATE future_schema.unknown_table";
                }
                """.formatted("\"\"\"" + System.lineSeparator() + "JOIN logistics.delivery"
                + System.lineSeparator() + "\"\"\"");
        List<String> tables = sqlTables(source);

        assertThat(tables).containsExactly("catalog_management.sellable_sku", "logistics.delivery",
                "warehouse.inventory_lot", "warehouse.stock_movement", "payments.receivable",
                "future_schema.unknown_table");
        List<String> findings = ownershipFindings("BC05", "Sample.java", tables, ownershipManifest());
        assertThat(findings).contains(
                "FOREIGN SQL: consumer=BC05 table=catalog_management.sellable_sku owner=BC03 file=Sample.java",
                "FOREIGN SQL: consumer=BC05 table=logistics.delivery owner=BC06 file=Sample.java",
                "FOREIGN SQL: consumer=BC05 table=payments.receivable owner=BC07 file=Sample.java",
                "OPEN ownership evidence missing: consumer=BC05 table=future_schema.unknown_table file=Sample.java");
        assertThat(findings).noneMatch(value -> value.contains("warehouse.inventory_lot")
                || value.contains("warehouse.stock_movement"));
    }

    @Test
    void consumerSqlReferencesOnlyOwnedOrExplicitlySharedTables() throws IOException {
        List<String> findings = runtimeSourceFindings(ownershipManifest());
        assertThat(findings).as("foreign or unclassified qualified SQL table references")
                .isEmpty();
    }

    @Test
    void runtimeBoundaryCompositionDoesNotTakePersistenceAuthority() throws IOException {
        Path compositionRoot = SOURCE_ROOT.resolve("bootstrap/runtime/boundaries");
        try (Stream<Path> sources = Files.walk(compositionRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertThat(sqlTables(Files.readString(source)))
                        .as("SQL-free runtime boundary composition: %s", source).isEmpty();
            }
        }
    }

    private static void assertOwners(Map<String, Ownership> ownership, String expected, String... tables) {
        for (String table : tables) {
            assertThat(ownership).containsKey(table);
            assertThat(ownership.get(table).owner()).as("owner for %s", table).isEqualTo(expected);
        }
    }

    private static Map<String, Ownership> ownershipManifest() throws IOException {
        List<String> lines = Files.readAllLines(OWNERSHIP_FILE);
        assertThat(lines).contains("table\towner\tevidence");
        Map<String, Ownership> result = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("table\t")) continue;
            String[] columns = line.split("\\t", -1);
            if (columns.length != 3) throw new AssertionError("Invalid ownership TSV row: " + line);
            Ownership previous = result.put(columns[0], new Ownership(columns[1], columns[2]));
            if (previous != null) throw new AssertionError("Duplicate ownership row: " + columns[0]);
        }
        return Map.copyOf(result);
    }

    private static Set<String> migrationTables() throws IOException {
        Set<String> result = new HashSet<>();
        for (Path migrationRoot : List.of(MIGRATION_ROOT, TENANT_MIGRATION_ROOT)) {
            try (Stream<Path> files = Files.list(migrationRoot)) {
                for (Path file : files.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                        .sorted().toList()) {
                    Matcher matcher = CREATED_TABLE.matcher(Files.readString(file));
                    while (matcher.find()) {
                        String table = (matcher.group(1) + "." + matcher.group(2)).toLowerCase(Locale.ROOT);
                        result.add(table);
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    private static List<String> runtimeSourceFindings(Map<String, Ownership> ownership) throws IOException {
        List<String> findings = new ArrayList<>();
        for (ConsumerRoot consumer : CONSUMERS) {
            Path root = SOURCE_ROOT.resolve(consumer.packageRoot());
            assertThat(Files.isDirectory(root)).as("consumer source root %s", root).isTrue();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                    findings.addAll(ownershipFindings(consumer.owner(), file.toString(),
                            sqlTables(Files.readString(file)), ownership));
                }
            }
        }
        return findings.stream().sorted().toList();
    }

    private static List<String> ownershipFindings(String consumer, String file, List<String> tables,
                                                   Map<String, Ownership> ownership) {
        List<String> findings = new ArrayList<>();
        for (String table : tables) {
            Ownership row = ownership.get(table);
            if (row == null) {
                findings.add("OPEN ownership evidence missing: consumer=" + consumer + " table=" + table + " file=" + file);
            } else if (row.owner().matches("BC\\d{2}") && !row.owner().equals(consumer)) {
                findings.add("FOREIGN SQL: consumer=" + consumer + " table=" + table + " owner=" + row.owner() + " file=" + file);
            }
        }
        return findings;
    }

    private static List<String> sqlTables(String source) {
        List<String> fragments = javaStringFragments(source);
        List<String> tables = new ArrayList<>();
        for (String fragment : fragments) {
            Matcher matcher = SQL_TABLE.matcher(fragment);
            while (matcher.find()) {
                tables.add((matcher.group(1) + "." + matcher.group(2)).toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(tables);
    }

    private static List<String> javaStringFragments(String source) {
        List<StringLiteral> literals = stringLiterals(source);
        List<String> fragments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        StringLiteral previous = null;
        for (StringLiteral literal : literals) {
            if (previous == null || !ONLY_CONCATENATION.matcher(source.substring(previous.end(), literal.start())).matches()) {
                if (previous != null) fragments.add(current.toString());
                current.setLength(0);
            }
            current.append(literal.value());
            previous = literal;
        }
        if (previous != null) fragments.add(current.toString());
        return fragments;
    }

    private static List<StringLiteral> stringLiterals(String source) {
        List<StringLiteral> literals = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            char value = source.charAt(index);
            if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index = skipLineComment(source, index + 2);
            } else if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                index = skipBlockComment(source, index + 2);
            } else if (value == '\'') {
                index = skipQuoted(source, index + 1, '\'');
            } else if (source.startsWith("\"\"\"", index)) {
                int start = index;
                int contentStart = index + 3;
                int end = skipTextBlock(source, contentStart);
                literals.add(new StringLiteral(start, end, decodeEscapes(source.substring(contentStart, end - 3))));
                index = end;
            } else if (value == '\"') {
                int start = index;
                int contentStart = index + 1;
                int end = skipQuoted(source, contentStart, '\"');
                literals.add(new StringLiteral(start, end, decodeEscapes(source.substring(contentStart, end - 1))));
                index = end;
            } else {
                index++;
            }
        }
        return literals;
    }

    private static int skipLineComment(String source, int index) {
        while (index < source.length() && source.charAt(index) != '\n') index++;
        return index;
    }

    private static int skipBlockComment(String source, int index) {
        int end = source.indexOf("*/", index);
        return end < 0 ? source.length() : end + 2;
    }

    private static int skipQuoted(String source, int index, char quote) {
        while (index < source.length()) {
            char value = source.charAt(index++);
            if (value == '\\' && index < source.length()) index++;
            else if (value == quote) break;
        }
        return index;
    }

    private static int skipTextBlock(String source, int index) {
        while (index < source.length()) {
            if (source.startsWith("\"\"\"", index)) {
                int slashes = 0;
                for (int cursor = index - 1; cursor >= 0 && source.charAt(cursor) == '\\'; cursor--) slashes++;
                if (slashes % 2 == 0) return index + 3;
            }
            index++;
        }
        return source.length();
    }

    private static String decodeEscapes(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                decoded.append(current);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"' -> decoded.append('"');
                case '\\' -> decoded.append('\\');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 's' -> decoded.append(' ');
                default -> decoded.append(escaped);
            }
        }
        return decoded.toString();
    }

    private record ConsumerRoot(String packageRoot, String owner) { }
    private record Ownership(String owner, String evidence) { }
    private record StringLiteral(int start, int end, String value) { }
}
