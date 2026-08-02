package com.nexa.api.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for real PostgreSQL integration tests: Testcontainers database, real
 * Flyway migrations, real security filters, real runtime adapters and the seeded
 * local workspace (owner/sales/warehouse/logistics/buyer at icisa-test).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
public abstract class PostgresIntegrationSupport {
	protected static final String TEST_PASSWORD = "integration-test-password";
	protected static final String WORKSPACE_SLUG = "icisa-test";
	protected static final String ALLOWED_ORIGIN = "http://localhost:4200";
	protected static final String OWNER_EMAIL = "owner@icisa-test.local";
	protected static final String SALES_EMAIL = "sales@icisa-test.local";
	protected static final String WAREHOUSE_EMAIL = "warehouse@icisa-test.local";
	protected static final String LOGISTICS_EMAIL = "logistics@icisa-test.local";
	protected static final String BUYER_EMAIL = "buyer@icisa-test.local";

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa")
			.withUsername("nexa")
			.withPassword("test-only-password");

	static {
		if (Boolean.getBoolean("nexa.integration.enabled")) {
			POSTGRES.start();
		}
	}

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("NEXA_DATABASE_URL", POSTGRES::getJdbcUrl);
		registry.add("NEXA_DATABASE_USERNAME", POSTGRES::getUsername);
		registry.add("NEXA_DATABASE_PASSWORD", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("NEXA_FLYWAY_ENABLED", () -> "true");
		registry.add("nexa.jdbc.adapters-enabled", () -> "true");
		registry.add("nexa.security.allow-ephemeral-keys", () -> "true");
		registry.add("nexa.security.issuer", () -> "http://test.local");
		registry.add("nexa.security.audience", () -> "nexa-test");
		registry.add("nexa.security.refresh-token-ttl", () -> "PT30M");
		registry.add("nexa.security.system-operator-token", () -> "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz");
		registry.add("nexa.security.reset.throttle-key", () -> "integration-reset-throttle-key-012345678901234567890123456789");
		registry.add("nexa.security.notification-outbox-key", () -> "integration-notification-outbox-key-012345678901234567890123456789");
		registry.add("NEXA_DEV_BOOTSTRAP_ENABLED", () -> "true");
		registry.add("NEXA_DEV_TENANT_NAME", () -> "ICISA Test");
		registry.add("NEXA_DEV_TENANT_SLUG", () -> WORKSPACE_SLUG);
		registry.add("NEXA_DEV_WORKSPACE_NAME", () -> "ICISA Test Workspace");
		registry.add("NEXA_DEV_WORKSPACE_SLUG", () -> WORKSPACE_SLUG);
		registry.add("NEXA_DEV_OWNER_EMAIL", () -> OWNER_EMAIL);
		registry.add("NEXA_DEV_OWNER_PASSWORD", () -> TEST_PASSWORD);
		registry.add("NEXA_DEV_SALES_EMAIL", () -> SALES_EMAIL);
		registry.add("NEXA_DEV_SALES_PASSWORD", () -> TEST_PASSWORD);
		registry.add("NEXA_DEV_WAREHOUSE_EMAIL", () -> WAREHOUSE_EMAIL);
		registry.add("NEXA_DEV_WAREHOUSE_PASSWORD", () -> TEST_PASSWORD);
		registry.add("NEXA_DEV_LOGISTICS_EMAIL", () -> LOGISTICS_EMAIL);
		registry.add("NEXA_DEV_LOGISTICS_PASSWORD", () -> TEST_PASSWORD);
		registry.add("NEXA_DEV_BUYER_EMAIL", () -> BUYER_EMAIL);
		registry.add("NEXA_DEV_BUYER_PASSWORD", () -> TEST_PASSWORD);
	}

	@BeforeEach
	void resetThrottleState() {
		jdbc.update("delete from iam.authentication_failure");
	}

	/** Signs in through the real HTTP stack and returns the access token. */
	protected String accessToken(String email, String surface) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/authentication/sign-in")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD
								+ "\",\"workspaceSlug\":\"" + WORKSPACE_SLUG + "\",\"surface\":\"" + surface + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		return tools.jackson.databind.json.JsonMapper.shared().readTree(body).get("accessToken").asText();
	}

	protected String membershipId(String email) {
		return jdbc.queryForObject("select m.id::text from tenant_management.workspace_membership m "
				+ "join iam.user_account u on u.id = m.user_id where u.normalized_email = ?", String.class, email);
	}

	protected String tenantId() {
		return jdbc.queryForObject("select tenant_id::text from tenant_management.workspace where slug = ?",
				String.class, WORKSPACE_SLUG);
	}

	protected String workspaceId() {
		return jdbc.queryForObject("select id::text from tenant_management.workspace where slug = ?",
				String.class, WORKSPACE_SLUG);
	}

	protected String buyerClientAccountId() {
		return jdbc.queryForObject("select cam.client_account_id::text from sales.client_account_membership cam "
				+ "join tenant_management.workspace_membership m on m.id = cam.workspace_membership_id "
				+ "join iam.user_account u on u.id = m.user_id where u.normalized_email = ? limit 1",
				String.class, BUYER_EMAIL);
	}

	protected static String uuid() {
		return UUID.randomUUID().toString();
	}
}
