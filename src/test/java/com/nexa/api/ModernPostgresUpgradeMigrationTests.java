package com.nexa.api;

import com.nexa.api.support.CentralMigrationTestBootstrap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        UUID individualUser = UUID.randomUUID();
        UUID individualMembership = UUID.randomUUID();
        UUID individualAccount = UUID.randomUUID();
        UUID purchaseRequest = UUID.randomUUID();
        try (var connection = POSTGRES.createConnection("")) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("insert into tenant_management.tenant (id,slug,name,status,created_at,updated_at) values ('" + tenant + "','upgrade-tenant','Upgrade Tenant','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into tenant_management.workspace (id,tenant_id,slug,name,status,created_at,updated_at) values ('" + workspace + "','" + tenant + "','upgrade-workspace','Upgrade Workspace','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at) values ('" + user + "','upgrade@example.invalid','UPGRADE@EXAMPLE.INVALID','upgrade-user','UPGRADE-USER','Upgrade User','es','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at) values ('" + membership + "','" + workspace + "','" + user + "','BUYER','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into tenant_management.operational_settings (workspace_id,updated_at) values ('" + workspace + "',current_timestamp)");
                statement.executeUpdate("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at) values ('" + account + "','" + tenant + "','" + workspace + "','UPGRADE-ACCOUNT','Upgrade Account','Upgrade Account','PE','RUC','20123456789','STANDARD','Upgrade User','upgrade@example.invalid','000000000','{}','NET30','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into sales.client_account_membership (client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) values ('" + account + "','" + membership + "','" + tenant + "','" + workspace + "','2026-01-01 00:00:00+00')");
                statement.executeUpdate("insert into sales.purchase_request (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,created_at,updated_at,submitted_at,expires_at) values ('" + purchaseRequest + "','" + tenant + "','" + workspace + "','" + account + "','" + membership + "','UPGRADE-PR','SUBMITTED','NORMAL','2026-01-01 00:00:00+00','2026-01-01 00:00:00+00','2026-01-01 00:00:00+00','2026-01-04 00:00:00+00')");
                statement.executeUpdate("insert into catalog_management.category (id,tenant_id,workspace_id,slug,name,status,version,created_at,updated_at) values ('" + category + "','" + tenant + "','" + workspace + "','upgrade-category','Upgrade Category','ACTIVE',0,current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into catalog_management.brand (id,tenant_id,workspace_id,slug,name,status,version,created_at,updated_at) values ('" + brand + "','" + tenant + "','" + workspace + "','upgrade-brand','Upgrade Brand','ACTIVE',0,current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into catalog_management.product (id,tenant_id,workspace_id,catalog_item_id,product_code,slug,name,description,category_id,brand_id,storage_temperature,status,version,created_at,updated_at) values ('" + product + "','" + tenant + "','" + workspace + "','UPGRADE-CATALOG','UPGRADE-PRODUCT','upgrade-product','Upgrade Product','Historical row preserved','" + category + "','" + brand + "','REFRIGERATED','ACTIVE',0,current_timestamp,current_timestamp)");
            }
        }

        Flyway currentMigrations = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        String latestResolvedMigration = Arrays.stream(currentMigrations.info().all())
                .filter(org.flywaydb.core.api.MigrationInfo::isVersioned)
                .map(org.flywaydb.core.api.MigrationInfo::getVersion)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("No resolved versioned migrations were found"))
                .getVersion();
        CentralMigrationTestBootstrap.migrateToCurrent(POSTGRES, () -> insertHistoricalIndividualBuyer(
                tenant, workspace, individualUser, individualMembership, individualAccount));

        try (var connection = POSTGRES.createConnection("")) {
            try (var statement = connection.createStatement(); var version = statement.executeQuery("select version from flyway_schema_history order by installed_rank desc limit 1")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getString(1)).isEqualTo(latestResolvedMigration);
            }
            try (var statement = connection.prepareStatement("select count(*) from catalog_management.product where id=?")) {
                statement.setObject(1, product);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1L);
                }
            }
            try (var statement = connection.prepareStatement("""
                    select membership.created_at, membership.updated_at, membership.status,
                           membership.individual_account_id, membership.version, account.account_type
                      from sales.client_account_membership membership
                      join sales.client_account account
                        on account.tenant_id = membership.tenant_id
                       and account.workspace_id = membership.workspace_id
                       and account.id = membership.client_account_id
                     where membership.client_account_id = ?
                       and membership.workspace_membership_id = ?
                    """)) {
                statement.setObject(1, individualAccount);
                statement.setObject(2, individualMembership);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("V189 preserves a historical individual Buyer relationship").isTrue();
                    assertThat(rows.getString("account_type")).isEqualTo("INDIVIDUAL");
                    assertThat(rows.getString("status")).isEqualTo("ACTIVE");
                    assertThat(rows.getObject("individual_account_id", UUID.class)).isEqualTo(individualAccount);
                    assertThat(rows.getLong("version")).isZero();
                    assertThat(rows.getTimestamp("updated_at").toInstant())
                            .as("V189 preserves the historical individual membership timestamp")
                            .isEqualTo(rows.getTimestamp("created_at").toInstant());
                    assertThat(rows.next()).isFalse();
                }
            }
            assertIndividualBuyerMembershipIsUnique(connection, tenant, workspace, individualAccount);
            try (var statement = connection.prepareStatement("""
                    select membership.created_at, membership.updated_at, membership.status,
                           membership.individual_account_id, membership.version, account.account_type
                      from sales.client_account_membership membership
                      join sales.client_account account
                        on account.tenant_id = membership.tenant_id
                       and account.workspace_id = membership.workspace_id
                       and account.id = membership.client_account_id
                     where membership.client_account_id = ?
                       and membership.workspace_membership_id = ?
                    """)) {
                statement.setObject(1, account);
                statement.setObject(2, membership);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("V189 keeps the historical Buyer account relationship").isTrue();
                    assertThat(rows.getTimestamp("updated_at").toInstant())
                            .as("V189 preserves the historical creation timestamp instead of inventing a change time")
                            .isEqualTo(rows.getTimestamp("created_at").toInstant());
                    assertThat(rows.getString("status")).isEqualTo("ACTIVE");
                    assertThat(rows.getObject("individual_account_id")).isNull();
                    assertThat(rows.getLong("version")).isZero();
                    assertThat(rows.getString("account_type")).isEqualTo("BUSINESS");
                    assertThat(rows.next()).isFalse();
                }
            }
            try (var statement = connection.prepareStatement("""
                    select membership.tenant_id, workspace.tenant_id
                      from tenant_management.workspace_membership membership
                      join tenant_management.workspace workspace on workspace.id = membership.workspace_id
                     where membership.id = ?
                    """)) {
                statement.setObject(1, membership);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("historical membership survives and is backfilled through V173 FORCE RLS").isTrue();
                    assertThat(rows.getObject(1, UUID.class)).as("V174 derives membership Tenant from its Workspace")
                            .isEqualTo(tenant);
                    assertThat(rows.getObject(2, UUID.class)).isEqualTo(tenant);
                    assertThat(rows.next()).isFalse();
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
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    select (select count(*)
                              from tenant_management.permission_definition permission
                             where permission.permission_key = 'inventory.temperature.audit.read'
                               and permission.permission_group = 'INVENTORY'
                               and permission.display_name = 'Audit lot temperatures'
                               and permission.description = 'Read persisted lot temperature observations within the active Tenant and Workspace'
                               and permission.reserved) as exact_permission,
                           (select count(*)
                              from tenant_management.role_permission role_permission
                              join tenant_management.role_definition role_definition
                                on role_definition.id = role_permission.role_id
                             where role_permission.permission_key = 'inventory.temperature.audit.read'
                               and role_definition.tenant_id is null
                               and role_definition.workspace_id is null
                               and role_definition.code = 'business_operations_manager'
                               and role_definition.role_type = 'SYSTEM_TEMPLATE'
                               and role_definition.status = 'ACTIVE') as bom_grants,
                           (select count(*)
                              from tenant_management.role_permission role_permission
                              join tenant_management.role_definition role_definition
                                on role_definition.id = role_permission.role_id
                             where role_permission.permission_key = 'inventory.temperature.audit.read'
                               and not (role_definition.tenant_id is null
                                        and role_definition.workspace_id is null
                                        and role_definition.code = 'business_operations_manager'
                                        and role_definition.role_type = 'SYSTEM_TEMPLATE'
                                        and role_definition.status = 'ACTIVE')) as other_grants
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("exact_permission")).as("V186 installs the approved temperature-audit permission metadata")
                        .isEqualTo(1L);
                assertThat(rows.getLong("bom_grants")).as("V186 grants the permission to the reserved BOM role")
                        .isEqualTo(1L);
                assertThat(rows.getLong("other_grants")).as("V186 does not grant temperature audit to other roles")
                        .isZero();
            }
        }
    }

    private static void insertHistoricalIndividualBuyer(UUID tenant, UUID workspace, UUID user,
            UUID membership, UUID account) {
        try (var connection = POSTGRES.createConnection("")) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at) values ('"
                        + user + "','upgrade-individual@example.invalid','UPGRADE-INDIVIDUAL@EXAMPLE.INVALID','upgrade-individual','UPGRADE-INDIVIDUAL','Upgrade Individual','es','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into tenant_management.workspace_membership (id,workspace_id,tenant_id,user_id,membership_type,status,created_at,updated_at) values ('"
                        + membership + "','" + workspace + "','" + tenant + "','" + user + "','BUYER','ACTIVE',current_timestamp,current_timestamp)");
                statement.executeUpdate("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at,account_type) values ('"
                        + account + "','" + tenant + "','" + workspace + "','UPGRADE-INDIVIDUAL','Upgrade Individual','Upgrade Individual',NULL,NULL,NULL,'STANDARD','Upgrade Individual','upgrade-individual@example.invalid','000000000','{}','NET30','ACTIVE',current_timestamp,current_timestamp,'INDIVIDUAL')");
                statement.executeUpdate("insert into sales.client_account_membership (client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) values ('"
                        + account + "','" + membership + "','" + tenant + "','" + workspace + "','2026-01-02 00:00:00+00')");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create the historical individual Buyer migration fixture", exception);
        }
    }

    private static void assertIndividualBuyerMembershipIsUnique(java.sql.Connection connection, UUID tenant,
            UUID workspace, UUID individualAccount) throws SQLException {
        UUID secondBuyer = UUID.randomUUID();
        UUID secondMembership = UUID.randomUUID();
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at) values ('"
                    + secondBuyer + "','upgrade-second-individual@example.invalid','UPGRADE-SECOND-INDIVIDUAL@EXAMPLE.INVALID','upgrade-second-individual','UPGRADE-SECOND-INDIVIDUAL','Upgrade Second Individual','es','ACTIVE',current_timestamp,current_timestamp)");
            statement.executeUpdate("insert into tenant_management.workspace_membership (id,workspace_id,tenant_id,user_id,membership_type,status,created_at,updated_at) values ('"
                    + secondMembership + "','" + workspace + "','" + tenant + "','" + secondBuyer + "','BUYER','ACTIVE',current_timestamp,current_timestamp)");
        }
        assertThatThrownBy(() -> {
            try (var statement = connection.prepareStatement("""
                    insert into sales.client_account_membership
                        (client_account_id, workspace_membership_id, tenant_id, workspace_id, created_at)
                    values (?, ?, ?, ?, current_timestamp)
                    """)) {
                statement.setObject(1, individualAccount);
                statement.setObject(2, secondMembership);
                statement.setObject(3, tenant);
                statement.setObject(4, workspace);
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("23505"));
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
