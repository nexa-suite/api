package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the public draft HTTP boundary with the least-privileged runtime
 * role. Existing integration tests use a bypass-RLS test role; this test keeps
 * the anonymous create/read/update path on the same forced-RLS boundary as the
 * deployed service.
 */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
class OrganizationRegistrationDraftHttpRlsIT {
    private static final String MIGRATOR_USERNAME = "nexa";
    private static final String MIGRATOR_PASSWORD = "test-only-password";
    private static final String RUNTIME_USERNAME = "nexa_runtime";
    private static final String RUNTIME_PASSWORD = "test-only-runtime-password";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("nexa")
            .withUsername(MIGRATOR_USERNAME)
            .withPassword(MIGRATOR_PASSWORD);

    static {
        if (Boolean.getBoolean("nexa.integration.enabled")) {
            POSTGRES.start();
            createRuntimeRole();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private UUID createdRegistration;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("NEXA_DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("NEXA_DATABASE_USERNAME", () -> RUNTIME_USERNAME);
        registry.add("NEXA_DATABASE_PASSWORD", () -> RUNTIME_PASSWORD);
        registry.add("NEXA_DATABASE_RUNTIME_USERNAME", () -> RUNTIME_USERNAME);
        registry.add("NEXA_DATABASE_RUNTIME_PASSWORD", () -> RUNTIME_PASSWORD);
        registry.add("NEXA_DATABASE_MIGRATOR_USERNAME", POSTGRES::getUsername);
        registry.add("NEXA_DATABASE_MIGRATOR_PASSWORD", POSTGRES::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USERNAME);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("NEXA_FLYWAY_ENABLED", () -> "true");
        registry.add("NEXA_DATABASE_REQUIRE_LEAST_PRIVILEGE_RUNTIME", () -> "true");
        registry.add("nexa.jdbc.adapters-enabled", () -> "true");
        registry.add("nexa.security.allow-ephemeral-keys", () -> "true");
        registry.add("nexa.security.issuer", () -> "http://test.local");
        registry.add("nexa.security.audience", () -> "nexa-test");
        registry.add("nexa.security.refresh-token-ttl", () -> "PT30M");
        registry.add("nexa.security.system-operator-token",
                () -> "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz");
        registry.add("nexa.security.reset.throttle-key",
                () -> "integration-reset-throttle-key-012345678901234567890123456789");
        registry.add("nexa.security.notification-outbox-key",
                () -> "integration-notification-outbox-key-012345678901234567890123456789");
        registry.add("NEXA_DEV_BOOTSTRAP_ENABLED", () -> "false");
    }

    @AfterEach
    void removeDraft() throws Exception {
        if (createdRegistration == null) return;
        try (Connection connection = openMigratorConnection();
             PreparedStatement idempotency = connection.prepareStatement(
                     "delete from tenant_management.organization_registration_draft_idempotency where registration_id=?");
             PreparedStatement registration = connection.prepareStatement(
                     "delete from tenant_management.organization_registration where id=?")) {
            idempotency.setObject(1, createdRegistration);
            idempotency.executeUpdate();
            registration.setObject(1, createdRegistration);
            registration.executeUpdate();
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @Test
    void createReadAndUpdateDraftAcrossHttpRequestsWithForcedRls() throws Exception {
        var created = mockMvc.perform(post("/api/v1/tenant-management/organization-registration-drafts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        var body = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(created.getResponse().getContentAsString());
        createdRegistration = UUID.fromString(body.get("registrationId").asText());
        String resumeToken = body.get("resumeToken").asText();
        String etag = created.getResponse().getHeader("ETag");
        mockMvc.perform(get("/api/v1/tenant-management/organization-registration-drafts/" + createdRegistration)
                        .header("X-Resume-Token", resumeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(put("/api/v1/tenant-management/organization-registration-drafts/" + createdRegistration + "/steps/1")
                        .header("X-Resume-Token", resumeToken)
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "http-rls-step-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"HTTP RLS Registration\",\"displayName\":\"HTTP RLS Registration\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.lastCompletedStep").value(1));

        mockMvc.perform(get("/api/v1/tenant-management/organization-registration-drafts/" + createdRegistration)
                        .header("X-Resume-Token", resumeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.data.step1.legalName").value("HTTP RLS Registration"));
    }

    @Test
    void rejectsMissingOrAlteredTokenAndAcceptsLegacyHeaderAlias() throws Exception {
        var created = mockMvc.perform(post("/api/v1/tenant-management/organization-registration-drafts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        var body = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(created.getResponse().getContentAsString());
        createdRegistration = UUID.fromString(body.get("registrationId").asText());
        String resumeToken = body.get("resumeToken").asText();
        String path = "/api/v1/tenant-management/organization-registration-drafts/" + createdRegistration;

        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRAFT_NOT_FOUND"));
        mockMvc.perform(get(path).header("X-Resume-Token", resumeToken + "-altered"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRAFT_NOT_FOUND"));
        mockMvc.perform(get(path).header("X-Organization-Registration-Token", resumeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationId").value(createdRegistration.toString()));
    }

    private static void createRuntimeRole() {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
                            EXECUTE 'CREATE ROLE nexa_runtime LOGIN PASSWORD ''test-only-runtime-password''';
                        END IF;
                        EXECUTE 'ALTER ROLE nexa_runtime LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD ''test-only-runtime-password''';
                    END
                    $$;
                    """);
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Connection openMigratorConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
    }

}
