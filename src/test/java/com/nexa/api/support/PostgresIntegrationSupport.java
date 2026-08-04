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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
	protected static final String MIGRATOR_USERNAME = "nexa";
	protected static final String MIGRATOR_PASSWORD = "test-only-password";
	protected static final String RUNTIME_USERNAME = "nexa_runtime";
	protected static final String RUNTIME_PASSWORD = "test-only-runtime-password";
	protected static final String TEST_DATABASE_USERNAME = "nexa_test";
	protected static final String TEST_DATABASE_PASSWORD = "test-only-test-password";
	protected static final String WORKSPACE_SLUG = "icisa-test";
	protected static final String ALLOWED_ORIGIN = "http://localhost:4200";
	protected static final String OWNER_EMAIL = "owner@icisa-test.local";
	protected static final String SALES_EMAIL = "sales@icisa-test.local";
	protected static final String WAREHOUSE_EMAIL = "warehouse@icisa-test.local";
	protected static final String LOGISTICS_EMAIL = "logistics@icisa-test.local";
	protected static final String BUYER_EMAIL = "buyer@icisa-test.local";

	protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName("nexa")
			.withUsername(MIGRATOR_USERNAME)
			.withPassword(MIGRATOR_PASSWORD);

	static {
		if (Boolean.getBoolean("nexa.integration.enabled")) {
			POSTGRES.start();
			createRuntimeRole();
			createTestRole();
		}
	}

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("NEXA_DATABASE_URL", POSTGRES::getJdbcUrl);
		registry.add("NEXA_DATABASE_USERNAME", () -> TEST_DATABASE_USERNAME);
		registry.add("NEXA_DATABASE_PASSWORD", () -> TEST_DATABASE_PASSWORD);
		registry.add("NEXA_DATABASE_RUNTIME_USERNAME", () -> TEST_DATABASE_USERNAME);
		registry.add("NEXA_DATABASE_RUNTIME_PASSWORD", () -> TEST_DATABASE_PASSWORD);
		registry.add("NEXA_DATABASE_MIGRATOR_USERNAME", POSTGRES::getUsername);
		registry.add("NEXA_DATABASE_MIGRATOR_PASSWORD", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", () -> TEST_DATABASE_USERNAME);
		registry.add("spring.datasource.password", () -> TEST_DATABASE_PASSWORD);
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
		ensureTestRolePrivileges();
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
				+ "join iam.user_account u on u.id = m.user_id where u.normalized_email = ? "
				+ "and m.workspace_id = (select id from tenant_management.workspace where slug = ?)", String.class, email, WORKSPACE_SLUG);
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

	public static Connection openRuntimeConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USERNAME, RUNTIME_PASSWORD);
	}

	public static Connection openMigratorConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
	}

	public static String runtimeJdbcUrl() {
		return POSTGRES.getJdbcUrl();
	}

	public static String runtimeDatabaseUsername() {
		return RUNTIME_USERNAME;
	}

	public static String runtimeDatabasePassword() {
		return RUNTIME_PASSWORD;
	}

	public static String migratorDatabaseUsername() {
		return MIGRATOR_USERNAME;
	}

	public static String migratorDatabasePassword() {
		return MIGRATOR_PASSWORD;
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

	private static void createTestRole() {
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
			 Statement statement = connection.createStatement()) {
			statement.execute("""
					DO $$
					BEGIN
					    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_test') THEN
					        EXECUTE 'CREATE ROLE nexa_test LOGIN PASSWORD ''test-only-test-password''';
					    END IF;
					    EXECUTE 'ALTER ROLE nexa_test LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS PASSWORD ''test-only-test-password''';
					END
					$$;
					""");
			statement.execute("GRANT " + MIGRATOR_USERNAME + " TO " + TEST_DATABASE_USERNAME);
			statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE nexa GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexa_test");
			statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE nexa GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO nexa_test");
			statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE nexa GRANT EXECUTE ON FUNCTIONS TO nexa_test");
		} catch (SQLException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static void ensureTestRolePrivileges() {
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
			 Statement statement = connection.createStatement()) {
			for (String schema : new String[]{"iam", "tenant_management", "catalog_management", "sales", "warehouse", "logistics", "business_documents", "payments", "notifications", "integration"}) {
				statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + TEST_DATABASE_USERNAME);
				statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + schema + " TO " + TEST_DATABASE_USERNAME);
				statement.execute("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA " + schema + " TO " + TEST_DATABASE_USERNAME);
				statement.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA " + schema + " TO " + TEST_DATABASE_USERNAME);
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Could not grant the integration test role its test-only privileges", exception);
		}
	}
}
