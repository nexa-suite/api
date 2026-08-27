package com.nexa.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies an existing V63 database upgrades in place without losing historical catalog rows. */
@Testcontainers(disabledWithoutDocker = true)
class ModernPostgresUpgradeMigrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("nexa")
            .withUsername("nexa")
            .withPassword("test-only-password");

    @Test
    void v63DatabaseUpgradesToLatestWithoutDroppingHistoricalCatalog() throws SQLException {
        try (var connection = POSTGRES.createConnection("")) {
            connection.createStatement().execute("create role nexa_runtime");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("63").load().migrate();

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
                statement.executeUpdate("insert into sales.purchase_request (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,created_at,updated_at,submitted_at) values ('" + purchaseRequest + "','" + tenant + "','" + workspace + "','" + account + "','" + membership + "','UPGRADE-PR','SUBMITTED','NORMAL','2026-01-01 00:00:00+00','2026-01-01 00:00:00+00','2026-01-01 00:00:00+00')");
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
                assertThat(version.getString(1)).isEqualTo("92");
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
}
