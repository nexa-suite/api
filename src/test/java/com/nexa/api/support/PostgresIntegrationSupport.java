package com.nexa.api.support;

import com.nexa.api.bootstrap.local.LocalTenantBusinessFixtureSeeder;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogSkuPersistenceBootstrap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for real PostgreSQL integration tests: Testcontainers database, real
 * Flyway migrations, real security filters, real runtime adapters and the seeded
 * local workspace (owner/sales/warehouse/logistics/buyer at icisa-test). Identity
 * and access assertions use the central database; business workflows use each
 * Tenant's provisioned physical database. Tests may override individual
 * capabilities explicitly when exercising unavailable-capability behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=",
		"spring.jpa.open-in-view=false",
		"spring.datasource.hikari.maximum-pool-size=4",
		"spring.datasource.hikari.minimum-idle=1",
		"nexa.tenant-business.router.maximum-cached-pools=2",
		"nexa.tenant-business.router.maximum-pool-size=2",
		"nexa.tenant-business.buyer-wallet-read.enabled=true",
		"nexa.tenant-business.buyer-wallet-recharge.enabled=true",
		"nexa.tenant-business.purchase-request.enabled=true",
		"nexa.tenant-business.catalog-detail-read.enabled=true",
		"nexa.tenant-business.catalog-management.enabled=true",
		"nexa.tenant-business.payments.enabled=true",
		"nexa.tenant-business.fulfillment-delivery.enabled=true",
		"nexa.tenant-business.warehouse-operations.enabled=true",
		"nexa.tenant-business.local-fixtures.enabled=true",
		"nexa.tenant-business.support-sales-order-read.enabled=true",
		"nexa.tenant-business.business-documents.enabled=true",
		"nexa.tenant-business.business-traceability.enabled=true",
		"nexa.tenant-business.notifications.enabled=true",
		"nexa.business-traceability.worker-delay-ms=3600000",
		"nexa.scheduling.enabled=false",
		"spring.modulith.moments.enabled=false"
})
@Import(TenantBusinessIntegrationTestConfiguration.class)
public abstract class PostgresIntegrationSupport {
	protected static final String TEST_PASSWORD = "integration-test-password";
	protected static final String MIGRATOR_USERNAME = "nexa";
	protected static final String MIGRATOR_PASSWORD = "test-only-password";
	private static final String FLYWAY_MIGRATOR_USERNAME = "nexa_migrator";
	private static final String FLYWAY_MIGRATOR_PASSWORD = "integration-migrator-" + UUID.randomUUID();
	private static final String WORKSPACE_SCOPE_READER_PASSWORD = "integration-scope-reader-" + UUID.randomUUID();
	private static final String INTERNAL_OPERATOR_PASSWORD = "integration-internal-operator-" + UUID.randomUUID();
	protected static final String RUNTIME_USERNAME = "nexa_runtime";
	protected static final String RUNTIME_PASSWORD = "test-only-runtime-password";
	protected static final String TEST_DATABASE_USERNAME = "nexa_test";
	protected static final String TEST_DATABASE_PASSWORD = "test-only-test-password";
	protected static final String BUSINESS_DOCUMENT_SCOPE_READER_PASSWORD = "test-only-business-document-scope-reader-password";
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
			createBusinessDocumentScopeReaderRole();
			bootstrapCentralMigrations();
		}
	}

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	private Environment environment;

	@Autowired(required = false)
	private CatalogPersistenceBootstrap catalogPersistenceBootstrap;

	@Autowired(required = false)
	private CatalogSkuPersistenceBootstrap catalogSkuPersistenceBootstrap;

	@Autowired
	private LocalTenantBusinessFixtureSeeder tenantBusinessFixtureSeeder;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("NEXA_DATABASE_URL", POSTGRES::getJdbcUrl);
		registry.add("NEXA_DATABASE_USERNAME", () -> TEST_DATABASE_USERNAME);
		registry.add("NEXA_DATABASE_PASSWORD", () -> TEST_DATABASE_PASSWORD);
		registry.add("NEXA_DATABASE_RUNTIME_USERNAME", () -> TEST_DATABASE_USERNAME);
		registry.add("NEXA_DATABASE_RUNTIME_PASSWORD", () -> TEST_DATABASE_PASSWORD);
		registry.add("NEXA_DATABASE_MIGRATOR_USERNAME", () -> FLYWAY_MIGRATOR_USERNAME);
		registry.add("NEXA_DATABASE_MIGRATOR_PASSWORD", () -> FLYWAY_MIGRATOR_PASSWORD);
		registry.add("NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD", () -> BUSINESS_DOCUMENT_SCOPE_READER_PASSWORD);
		registry.add("NEXA_WORKSPACE_SCOPE_READER_PASSWORD", () -> WORKSPACE_SCOPE_READER_PASSWORD);
		registry.add("NEXA_INTERNAL_OPERATOR_DATABASE_PASSWORD", () -> INTERNAL_OPERATOR_PASSWORD);
		registry.add("nexa.tenant-business.local-credentials-directory", TenantBusinessIntegrationFixture::credentialDirectory);
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
		if (tenantBusinessRoutingEnabled()) {
			UUID tenant = UUID.fromString(tenantId());
			UUID workspace = UUID.fromString(workspaceId());
			provisionTenantBusinessDatabase(tenant, workspace);
			seedTenantBusinessFixture(tenant, workspace);
		}
	}

	private void seedTenantBusinessFixture(UUID tenantId, UUID workspaceId) {
		seedTenantCatalogFixture(tenantId, workspaceId);
		tenantBusinessFixtureSeeder.seed(tenantJdbcFor(tenantId), tenantId, workspaceId,
				UUID.fromString(membershipId(BUYER_EMAIL)), "CLI-001");
	}

	protected void seedTenantCatalogFixture(UUID tenantId, UUID workspaceId) {
		if (catalogPersistenceBootstrap == null || catalogSkuPersistenceBootstrap == null) {
			throw new IllegalStateException("Reviewed Tenant catalog fixture seeders are not available");
		}
		JdbcTemplate tenantJdbc = tenantJdbcFor(tenantId);
		catalogPersistenceBootstrap.importExactTenantWorkspace(tenantJdbc, tenantId, workspaceId);
		catalogSkuPersistenceBootstrap.reconcileExactTenantWorkspace(tenantJdbc, tenantId, workspaceId);
	}

	/** Direct test-only SQL handle for assertions/seeding against the actual routed Tenant database. */
	protected JdbcTemplate tenantJdbc() {
		return tenantJdbcFor(UUID.fromString(tenantId()));
	}

	/** Direct test-only SQL handle for an explicitly provisioned Tenant database. */
	protected JdbcTemplate tenantJdbcFor(UUID tenantId) {
		return TenantBusinessIntegrationFixture.jdbcFor(tenantId);
	}

	/** Provisions the physical Tenant database and central READY binding for an existing exact Workspace. */
	protected void provisionTenantBusinessDatabase(UUID tenantId, UUID workspaceId) {
		TenantBusinessIntegrationFixture.provision(jdbc, tenantId, workspaceId);
	}

	/** Signs in through the real HTTP stack and returns the access token. */
	protected String accessToken(String email, String surface) throws Exception {
		return accessTokenForWorkspace(email, surface, WORKSPACE_SLUG);
	}

	/** Signs into an explicitly selected Workspace through the real browser CSRF flow. */
	protected String accessTokenForWorkspace(String email, String workspaceSlug) throws Exception {
		return accessTokenForWorkspace(email, "PLATFORM", workspaceSlug);
	}

	protected String accessTokenForWorkspace(String email, String surface, String workspaceSlug) throws Exception {
		MvcResult result = mockMvc.perform(withBrowserCsrf(post("/api/v1/authentication/sign-in")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD
								+ "\",\"workspaceSlug\":\"" + workspaceSlug + "\",\"surface\":\"" + surface + "\"}")))
				.andExpect(status().isOk())
				.andReturn();
		return tools.jackson.databind.json.JsonMapper.shared()
				.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
	}

	/** Adds the cookie and request token from the browser CSRF bootstrap to a browser-session POST. */
	protected MockHttpServletRequestBuilder withBrowserCsrf(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult csrfBootstrap = mockMvc.perform(get("/api/v1/authentication/csrf")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
				.andExpect(status().isOk())
				.andReturn();
		String csrfToken = tools.jackson.databind.json.JsonMapper.shared()
				.readTree(csrfBootstrap.getResponse().getContentAsString()).path("token").asText();
		var csrfCookie = csrfBootstrap.getResponse().getCookie("NEXA_CSRF");
		if (csrfToken.isBlank() || csrfCookie == null) {
			throw new IllegalStateException("Browser CSRF bootstrap must return its request token and cookie");
		}
		return request.cookie(csrfCookie).header("X-Nexa-CSRF", csrfToken);
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
		UUID buyerMembership = UUID.fromString(membershipId(BUYER_EMAIL));
		JdbcTemplate businessJdbc = tenantBusinessRoutingEnabled() ? tenantJdbc() : jdbc;
		return businessJdbc.queryForObject("select client_account_id::text from sales.client_account_membership "
				+ "where tenant_id=? and workspace_id=? and workspace_membership_id=?",
				String.class, UUID.fromString(tenantId()), UUID.fromString(workspaceId()), buyerMembership);
	}

	protected static String uuid() {
		return UUID.randomUUID().toString();
	}

	private boolean tenantBusinessRoutingEnabled() {
		return java.util.Arrays.stream(new String[]{"buyer-wallet-read", "buyer-wallet-recharge", "purchase-request", "catalog-detail-read",
				"catalog-management", "payments", "fulfillment-delivery", "warehouse-operations", "local-fixtures",
				"support-sales-order-read", "business-documents", "business-traceability", "notifications"})
				.anyMatch(capability -> environment.getProperty(
						"nexa.tenant-business." + capability + ".enabled", Boolean.class, false));
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

	public static HikariDataSource workspaceScopeReaderPool() {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(POSTGRES.getJdbcUrl());
		config.setUsername("nexa_workspace_scope_reader_login");
		config.setPassword(WORKSPACE_SCOPE_READER_PASSWORD);
		config.setMaximumPoolSize(2);
		config.setMinimumIdle(0);
		config.setPoolName("workspace-scope-reader-it");
		config.setReadOnly(true);
		return new HikariDataSource(config);
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
					    EXECUTE 'ALTER ROLE nexa_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD ''test-only-runtime-password''';
					END
					$$;
					""");
		} catch (SQLException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	/**
	 * Rehearses the production direct-login migration boundary on the disposable
	 * Testcontainers database. V169's checked-in preparation script transfers
	 * only the reviewed ownership and grants before Flyway switches identities.
	 */
	private static void bootstrapCentralMigrations() {
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
				MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
			Statement statement = connection.createStatement()) {
			statement.execute("CREATE ROLE " + FLYWAY_MIGRATOR_USERNAME
					+ " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
					+ FLYWAY_MIGRATOR_PASSWORD + "'");
			statement.execute("CREATE ROLE nexa_tenant_database_provisioner LOGIN NOINHERIT NOSUPERUSER "
					+ "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_workspace_scope_reader NOLOGIN NOINHERIT NOSUPERUSER "
					+ "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_workspace_scope_reader_login LOGIN NOINHERIT NOSUPERUSER "
					+ "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
					+ WORKSPACE_SCOPE_READER_PASSWORD + "'");
			statement.execute("GRANT nexa_workspace_scope_reader TO nexa_workspace_scope_reader_login "
					+ "WITH INHERIT FALSE, SET TRUE, ADMIN FALSE");
			statement.execute("CREATE ROLE nexa_internal_operator NOLOGIN NOINHERIT NOSUPERUSER "
					+ "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_internal_operator_login LOGIN NOINHERIT NOSUPERUSER "
					+ "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
					+ INTERNAL_OPERATOR_PASSWORD + "'");
			statement.execute("GRANT nexa_internal_operator TO nexa_internal_operator_login "
					+ "WITH INHERIT FALSE, SET TRUE, ADMIN FALSE");
		} catch (SQLException exception) {
			throw new ExceptionInInitializerError(exception);
		}

		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target("168").load().migrate();

		List<Path> preparations;
		try (var scripts = Files.list(Path.of("ops/database"))) {
			preparations = scripts.filter(path -> path.getFileName().toString().matches("prepare-v[0-9]+-.+\\.sql"))
					.filter(path -> preparationVersion(path) > 168)
					.sorted(Comparator.comparingInt(PostgresIntegrationSupport::preparationVersion))
					.toList();
		} catch (Exception exception) {
			throw new ExceptionInInitializerError(exception);
		}

		int appliedVersion = 168;
		for (Path preparation : preparations) {
			int version = preparationVersion(preparation);
			if (version - 1 > appliedVersion) migrateCentralThrough(version - 1);
			applyMigrationPreparation(preparation);
			migrateCentralThrough(version);
			appliedVersion = version;
		}
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), FLYWAY_MIGRATOR_USERNAME, FLYWAY_MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").load().migrate();
		ensureTestRolePrivileges();
	}

	private static int preparationVersion(Path preparation) {
		String name = preparation.getFileName().toString();
		int separator = name.indexOf('-', "prepare-v".length());
		return Integer.parseInt(name.substring("prepare-v".length(), separator));
	}

	private static void migrateCentralThrough(int version) {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), FLYWAY_MIGRATOR_USERNAME, FLYWAY_MIGRATOR_PASSWORD)
				.locations("classpath:db/migration").target(Integer.toString(version)).load().migrate();
	}

	private static void applyMigrationPreparation(Path preparation) {
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
				MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
			Statement statement = connection.createStatement()) {
			statement.execute(Files.readString(preparation));
		} catch (Exception exception) {
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

	private static void createBusinessDocumentScopeReaderRole() {
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATOR_USERNAME, MIGRATOR_PASSWORD);
			 Statement statement = connection.createStatement()) {
			statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_documents_scope_reader') "
					+ "THEN EXECUTE 'CREATE ROLE nexa_business_documents_scope_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
					+ "NOREPLICATION NOBYPASSRLS PASSWORD ''" + BUSINESS_DOCUMENT_SCOPE_READER_PASSWORD
					+ "'''; END IF; END $$");
			statement.execute("ALTER ROLE nexa_business_documents_scope_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB "
					+ "NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + BUSINESS_DOCUMENT_SCOPE_READER_PASSWORD + "'");
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
