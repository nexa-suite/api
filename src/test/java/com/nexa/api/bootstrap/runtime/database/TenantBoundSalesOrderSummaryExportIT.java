package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.boundaries.TenantBoundSalesOrderSummaryExportUseCase;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.ClientAccountPersistenceAdapter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryRenderer;
import com.nexa.api.salescommitment.application.salesorder.export.service.SalesOrderSummaryExportService;
import com.nexa.api.salescommitment.application.salesorder.export.service.SalesOrderSummaryRendererStrategy;
import com.nexa.api.salescommitment.infrastructure.export.CsvSalesOrderSummaryRenderer;
import com.nexa.api.salescommitment.infrastructure.export.JdbcSalesOrderSummaryProjectionAdapter;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderSummaryExportFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises order-summary projection and Buyer relationship lookup across physical Tenant databases. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantBoundSalesOrderSummaryExportIT {
	private static final String MIGRATOR = "nexa_migrator";
	private static final String MIGRATOR_PASSWORD = "summary-export-migrator-test-only";
	private static final String RUNTIME = "nexa_runtime";
	private static final String RUNTIME_PASSWORD = "summary-export-runtime-test-only";
	private static final String POLICY_WRITER = "nexa_policy_snapshot_writer";
	private static final String POLICY_WRITER_PASSWORD = "summary-export-policy-test-only";
	private static final List<String> WORKER_ROLES = List.of("nexa_wallet_recharge_worker",
			"nexa_business_documents_worker", "nexa_payments_worker", "nexa_business_traceability_worker",
			"nexa_warehouse_reservation_expiry_worker");

	@Container
	private static final PostgreSQLContainer TENANT_A = tenant("summary-export-a");
	@Container
	private static final PostgreSQLContainer TENANT_B = tenant("summary-export-b");

	private static TenantFixture fixtureA;
	private static TenantFixture fixtureB;
	private static TenantBusinessDatabaseRouter router;
	private static TenantBoundSalesOrderSummaryExportUseCase exports;
	private static final AtomicInteger authorityLookups = new AtomicInteger();

	@BeforeAll
	static void prepareTenantDatabases() throws SQLException {
		fixtureA = prepare(TENANT_A, "A");
		fixtureB = prepare(TENANT_B, "B");
		Map<String, TenantBusinessDatabaseCredentials> credentials = Map.of(
				fixtureA.secretReference(), credentials(TENANT_A),
				fixtureB.secretReference(), credentials(TENANT_B));
		TenantBusinessDatabaseAuthority authority = presented -> {
			authorityLookups.incrementAndGet();
			TenantFixture fixture = fixtureA.tenantId().equals(presented.tenantId()) ? fixtureA
					: fixtureB.tenantId().equals(presented.tenantId()) ? fixtureB : null;
			if (fixture == null) throw new AccessDeniedException("Unknown Tenant fixture");
			return new TenantBusinessDatabaseBinding(fixture.tenantId(), fixture.databaseIdentity(),
					fixture.secretReference());
		};
		TenantBusinessDatabaseDataSourceFactory dataSources = binding -> {
			TenantBusinessDatabaseCredentials connection = credentials.get(binding.credentialSecretReference());
			if (connection == null) throw new IllegalStateException("No Tenant fixture credentials");
			HikariConfig config = new HikariConfig();
			config.setJdbcUrl(connection.jdbcUrl());
			config.setUsername(connection.username());
			config.setPassword(connection.password());
			config.setMaximumPoolSize(2);
			config.setPoolName("summary-export-" + binding.databaseIdentity());
			return new HikariDataSource(config);
		};
		TenantCustomerAccountQueryFactory accounts = tenantJdbc -> new ClientAccountPersistenceAdapter(tenantJdbc);
		router = new TenantBusinessDatabaseRouter(authority, dataSources, 2);
		List<SalesOrderSummaryRenderer> renderers = List.of(new CsvSalesOrderSummaryRenderer());
		SalesOrderSummaryRendererStrategy rendererStrategy = new SalesOrderSummaryRendererStrategy(renderers);
		TenantSalesOrderSummaryExportFactory exportFactory = (tenantJdbc, tenantAccounts) ->
				new SalesOrderSummaryExportService(new JdbcSalesOrderSummaryProjectionAdapter(tenantJdbc),
						tenantAccounts, rendererStrategy);
		exports = new TenantBoundSalesOrderSummaryExportUseCase(router, accounts, exportFactory);
	}

	@AfterAll
	static void closeTenantPools() {
		if (router != null) router.close();
	}

	@Test
	void exportsEachOrderAndItsLinesFromItsOwnPhysicalTenantDatabase() {
		String csvA = export(ownerContext(fixtureA), fixtureA.orderId());
		String csvB = export(ownerContext(fixtureB), fixtureB.orderId());

		assertThat(csvA).contains(fixtureA.orderNumber(), fixtureA.itemName())
				.doesNotContain(fixtureB.orderNumber(), fixtureB.itemName());
		assertThat(csvB).contains(fixtureB.orderNumber(), fixtureB.itemName())
				.doesNotContain(fixtureA.orderNumber(), fixtureA.itemName());
	}

	@Test
	void orderIdFromAnotherTenantIsNotFound() {
		assertThatThrownBy(() -> export(ownerContext(fixtureA), fixtureB.orderId()))
				.isInstanceOf(SalesResourceNotFoundException.class)
				.hasMessage("sales-order");
	}

	@Test
	void buyerCanExportOnlyAccountRelatedOrdersFromCurrentTenant() {
		String ownOrder = export(buyerContext(fixtureA, fixtureA.buyerMembershipId()), fixtureA.orderId());
		assertThat(ownOrder).contains(fixtureA.orderNumber(), fixtureA.itemName())
				.doesNotContain(fixtureB.orderNumber(), fixtureB.itemName());

		assertThatThrownBy(() -> export(buyerContext(fixtureA, fixtureA.unrelatedMembershipId()), fixtureA.orderId()))
				.isInstanceOf(SalesResourceNotFoundException.class)
				.hasMessage("client-account");
		assertThatThrownBy(() -> export(buyerContext(fixtureA, fixtureA.buyerMembershipId()), fixtureB.orderId()))
				.isInstanceOf(SalesResourceNotFoundException.class)
				.hasMessage("sales-order");
	}

	@Test
	void rejectsMissingExportPermissionBeforeResolvingTenantDatabase() {
		int lookupsBefore = authorityLookups.get();
		CurrentAccessContext warehouse = context(fixtureA, MembershipRole.WAREHOUSE, Surface.PLATFORM,
				UUID.randomUUID());

		assertThatThrownBy(() -> export(warehouse, fixtureA.orderId()))
				.isInstanceOf(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation.class);
		assertThat(authorityLookups).hasValue(lookupsBefore);
	}

	private static String export(CurrentAccessContext context, UUID orderId) {
		return new String(exports.export(context, orderId.toString(), SalesOrderSummaryExportFormat.CSV).content(),
				StandardCharsets.UTF_8);
	}

	private static CurrentAccessContext ownerContext(TenantFixture fixture) {
		return context(fixture, MembershipRole.COMPANY_OWNER, Surface.PLATFORM, fixture.ownerMembershipId());
	}

	private static CurrentAccessContext buyerContext(TenantFixture fixture, UUID membershipId) {
		return context(fixture, MembershipRole.BUYER, Surface.PORTAL, membershipId);
	}

	private static CurrentAccessContext context(TenantFixture fixture, MembershipRole role, Surface surface,
			UUID membershipId) {
		Membership membership = new Membership(new MembershipId(membershipId), new UserId(UUID.randomUUID()),
				fixture.tenantId(), fixture.workspaceId(), Set.of(role), MembershipStatus.ACTIVE);
		return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE, WorkspaceStatus.ACTIVE),
				surface);
	}

	private static TenantFixture prepare(PostgreSQLContainer database, String suffix) throws SQLException {
		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		UUID databaseIdentity = UUID.randomUUID();
		UUID buyerMembershipId = UUID.randomUUID();
		UUID ownerMembershipId = UUID.randomUUID();
		UUID unrelatedMembershipId = UUID.randomUUID();
		UUID clientAccountId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		String secretReference = "test/tenant-summary-export/" + suffix;
		String orderNumber = "SO-TEST-" + suffix;
		String itemName = "Tenant " + suffix + " item";

		provisionRoles(database);
		migrate(database);
		seedScope(database, tenantId, workspaceId, databaseIdentity);
		seedBuyerAccount(database, tenantId, workspaceId, clientAccountId, buyerMembershipId, suffix);
		seedOrder(database, tenantId, workspaceId, clientAccountId, ownerMembershipId, orderId,
				orderNumber, itemName, suffix);
		return new TenantFixture(new TenantId(tenantId), new WorkspaceId(workspaceId), databaseIdentity,
				buyerMembershipId, ownerMembershipId, unrelatedMembershipId, clientAccountId, orderId,
				secretReference, orderNumber, itemName);
	}

	private static void provisionRoles(PostgreSQLContainer database) throws SQLException {
		try (Connection connection = adminConnection(database); Statement statement = connection.createStatement()) {
			statement.execute("CREATE ROLE " + MIGRATOR + " LOGIN NOINHERIT PASSWORD '" + MIGRATOR_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE " + RUNTIME + " LOGIN NOINHERIT PASSWORD '" + RUNTIME_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE " + POLICY_WRITER + " LOGIN PASSWORD '" + POLICY_WRITER_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			for (String role : WORKER_ROLES) {
				statement.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			}
			statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + database.getDatabaseName() + "\" TO " + MIGRATOR);
			statement.execute("GRANT CONNECT ON DATABASE \"" + database.getDatabaseName() + "\" TO "
					+ RUNTIME + ", " + POLICY_WRITER);
			statement.execute("GRANT CREATE ON SCHEMA public TO " + MIGRATOR);
		}
	}

	private static void migrate(PostgreSQLContainer database) {
		Path location = Path.of("src/main/resources/db/tenant-migration").toAbsolutePath().normalize();
		Flyway.configure().dataSource(database.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
				.locations("filesystem:" + location).load().migrate();
	}

	private static void seedScope(PostgreSQLContainer database, UUID tenantId, UUID workspaceId,
			UUID databaseIdentity) {
		JdbcTemplate admin = adminJdbc(database);
		admin.update("INSERT INTO nexa_platform.tenant_business_database_identity(singleton,tenant_id,database_identity) "
				+ "VALUES (true,?,?)", tenantId, databaseIdentity);
		admin.update("INSERT INTO nexa_platform.tenant_workspace_scope_anchor(tenant_id,workspace_id) VALUES (?,?)",
				tenantId, workspaceId);
	}

	private static void seedBuyerAccount(PostgreSQLContainer database, UUID tenantId, UUID workspaceId,
			UUID accountId, UUID membershipId, String suffix) {
		Timestamp now = Timestamp.from(Instant.parse("2026-10-20T12:00:00Z"));
		String taxIdentifier = "20" + String.format(java.util.Locale.ROOT, "%09d",
				Math.floorMod(accountId.getLeastSignificantBits(), 1_000_000_000L));
		JdbcTemplate admin = adminJdbc(database);
		admin.update("INSERT INTO sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,"
				+ "tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
				+ "delivery_profile,payment_condition,status,created_at,updated_at) "
				+ "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
				accountId, tenantId, workspaceId, "SUMMARY-" + suffix, "Buyer " + suffix, "Buyer " + suffix,
				"PE", "RUC", taxIdentifier, "B2B",
				"Contact " + suffix, suffix.toLowerCase() + "@example.test", "+51999999999", "Delivery profile",
				"PREPAID", now, now);
		admin.update("INSERT INTO sales.client_account_membership "
				+ "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) VALUES (?,?,?,?,?)",
				accountId, membershipId, tenantId, workspaceId, now);
	}

	private static void seedOrder(PostgreSQLContainer database, UUID tenantId, UUID workspaceId,
			UUID accountId, UUID createdByMembershipId, UUID orderId, String orderNumber, String itemName, String suffix) {
		Timestamp now = Timestamp.from(Instant.parse("2026-10-20T12:00:00Z"));
		JdbcTemplate admin = adminJdbc(database);
		admin.update("INSERT INTO sales.sales_order (id,tenant_id,workspace_id,number,client_account_id,"
				+ "created_by_membership_id,priority,currency,total_amount,status,created_at,updated_at,order_source,origin_type) "
				+ "VALUES (?,?,?,?,?,?,'NORMAL','PEN',12.5000,'CONFIRMED',?,?,'MANUAL','MANUAL')",
				orderId, tenantId, workspaceId, orderNumber, accountId, createdByMembershipId, now, now);
		admin.update("INSERT INTO sales.sales_order_line (id,sales_order_id,catalog_item_id,item_name_snapshot,"
				+ "presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,line_subtotal,created_at) "
				+ "VALUES (?,?,?,?,?,2.0000,'BOX',6.2500,'PEN',12.5000,?)",
				UUID.randomUUID(), orderId, "TEST-" + suffix, itemName, "Box of " + suffix, now);
	}

	private static JdbcTemplate adminJdbc(PostgreSQLContainer database) {
		return new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
	}

	private static TenantBusinessDatabaseCredentials credentials(PostgreSQLContainer database) {
		return new TenantBusinessDatabaseCredentials(database.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
	}

	private static Connection adminConnection(PostgreSQLContainer database) throws SQLException {
		return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
	}

	private static PostgreSQLContainer tenant(String name) {
		return new PostgreSQLContainer("postgres:18.4-alpine").withDatabaseName(name)
				.withUsername("postgres").withPassword("summary-export-admin-test-only");
	}

	private record TenantFixture(TenantId tenantId, WorkspaceId workspaceId, UUID databaseIdentity,
			UUID buyerMembershipId, UUID ownerMembershipId, UUID unrelatedMembershipId, UUID clientAccountId,
			UUID orderId, String secretReference, String orderNumber, String itemName) { }
}
