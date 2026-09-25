package com.nexa.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the exact published v0.17.1 migration baseline upgrades without losing historical rows. */
@Testcontainers(disabledWithoutDocker = true)
class ModernPostgresUpgradeMigrationTests {
    private static final String PUBLISHED_V0171_COMMIT = "be03488df7b3442ad0016af822667817c3fcddab";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("nexa")
            .withUsername("nexa")
            .withPassword("test-only-password");

    @Test
    void publishedV0171BaselineUpgradesWithoutDroppingHistoricalRows() throws Exception {
        try (var connection = POSTGRES.createConnection("")) {
            connection.createStatement().execute("create role nexa_runtime");
        }
        Path releasedMigrationDirectory = extractPublishedV0171Migrations();
        Path extractedRepository = releasedMigrationDirectory.getParent().getParent().getParent().getParent().getParent();
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("filesystem:" + releasedMigrationDirectory.toAbsolutePath())
                    .target("100").load().migrate();
        } finally {
            deleteTree(extractedRepository);
        }

        UUID tenant = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID purchaseRequest = UUID.randomUUID();
        try (var connection = POSTGRES.createConnection("")) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("insert into tenant_management.tenant (id,slug,name,status,created_at,updated_at) values ('" + tenant + "','upgrade-tenant','Upgrade Tenant','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into tenant_management.workspace (id,tenant_id,slug,name,status,created_at,updated_at) values ('" + workspace + "','" + tenant + "','upgrade-workspace','Upgrade Workspace','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at) values ('" + user + "','upgrade@example.invalid','UPGRADE@EXAMPLE.INVALID','upgrade-user','UPGRADE-USER','Upgrade User','es','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at) values ('" + membership + "','" + workspace + "','" + user + "','BUYER','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into tenant_management.operational_settings (workspace_id,updated_at) values ('" + workspace + "',current_timestamp)");
                statement.executeUpdate("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at) values ('" + account + "','" + tenant + "','" + workspace + "','UPGRADE-ACCOUNT','Upgrade Account','Upgrade Account','PE','RUC','20123456789','STANDARD','Upgrade User','upgrade@example.invalid','000000000','{}','NET30','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into sales.purchase_request (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,created_at,updated_at,submitted_at,expires_at) values ('" + purchaseRequest + "','" + tenant + "','" + workspace + "','" + account + "','" + membership + "','UPGRADE-PR','SUBMITTED','NORMAL','2026-01-01 00:00:00+00','2026-01-01 00:00:00+00','2026-01-01 00:00:00+00','2026-01-04 00:00:00+00')");
                statement.executeUpdate("insert into catalog_management.category (id,tenant_id,workspace_id,slug,name,status,version,created_at,updated_at) values ('" + category + "','" + tenant + "','" + workspace + "','upgrade-category','Upgrade Category','ACTIVE',0,current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into catalog_management.brand (id,tenant_id,workspace_id,slug,name,status,version,created_at,updated_at) values ('" + brand + "','" + tenant + "','" + workspace + "','upgrade-brand','Upgrade Brand','ACTIVE',0,current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into catalog_management.product (id,tenant_id,workspace_id,catalog_item_id,product_code,slug,name,description,category_id,brand_id,storage_temperature,status,version,created_at,updated_at) values ('" + product + "','" + tenant + "','" + workspace + "','UPGRADE-CATALOG','UPGRADE-PRODUCT','upgrade-product','Upgrade Product','Historical row preserved','" + category + "','" + brand + "','REFRIGERATED','ACTIVE',0,current_timestamp,current_timestamp)");
            }
        }

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (var connection = POSTGRES.createConnection("")) {
            try (var statement = connection.createStatement(); var version = statement.executeQuery("select version from flyway_schema_history order by installed_rank desc limit 1")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getString(1)).isEqualTo("107");
            }
            try (var statement = connection.prepareStatement("select count(*) from catalog_management.product where id=?")) {
                statement.setObject(1, product);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1L);
                }
            }
            try (var statement = connection.prepareStatement("select purchase_request_expiry_days,expires_at from tenant_management.operational_settings settings join sales.purchase_request request on request.workspace_id=settings.workspace_id where request.id=?")) {
                statement.setObject(1, purchaseRequest);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(3);
                    assertThat(rows.getTimestamp(2).toInstant()).isEqualTo(java.time.Instant.parse("2026-01-04T00:00:00Z"));
                }
            }
        }
    }

    private static Path extractPublishedV0171Migrations() throws IOException, InterruptedException {
        Path repository = repositoryRoot();
        String taggedCommit = runGit(repository, "rev-parse", "--verify", "refs/tags/v0.17.1^{commit}");
        if (!PUBLISHED_V0171_COMMIT.equals(taggedCommit)) {
            throw new IllegalStateException("Expected v0.17.1 at " + PUBLISHED_V0171_COMMIT + " but found " + taggedCommit);
        }

        Path extractedRepository = Files.createTempDirectory("nexa-v0171-migration-baseline-");
        Path archiveError = extractedRepository.resolve("git-archive.stderr");
        Path extractError = extractedRepository.resolve("tar-extract.stderr");
        Process archive = new ProcessBuilder("git", "-C", repository.toString(), "archive", "--format=tar",
                PUBLISHED_V0171_COMMIT, "src/main/resources/db/migration")
                .redirectError(archiveError.toFile()).start();
        Process extract = new ProcessBuilder("tar", "-xf", "-", "-C", extractedRepository.toString())
                .redirectError(extractError.toFile()).start();
        try (var input = archive.getInputStream(); var output = extract.getOutputStream()) {
            input.transferTo(output);
        }
        int archiveExit = archive.waitFor();
        int extractExit = extract.waitFor();
        if (archiveExit != 0 || extractExit != 0) {
            String gitDetails = Files.exists(archiveError) ? Files.readString(archiveError, StandardCharsets.UTF_8) : "";
            String tarDetails = Files.exists(extractError) ? Files.readString(extractError, StandardCharsets.UTF_8) : "";
            deleteTree(extractedRepository);
            throw new IOException("Could not extract exact v0.17.1 migrations: git=" + gitDetails + "; tar=" + tarDetails);
        }
        return extractedRepository.resolve("src/main/resources/db/migration");
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) return current;
            current = current.getParent();
        }
        throw new IOException("The v0.17.1 upgrade test requires a checked-out Git repository with the published tag");
    }

    private static String runGit(Path repository, String... arguments) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new IOException("Git command failed: " + String.join(" ", command) + ": " + output);
        return output;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
