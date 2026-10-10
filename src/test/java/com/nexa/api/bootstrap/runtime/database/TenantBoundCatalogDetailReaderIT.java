package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.boundaries.CatalogClientAccountCompositionAdapter;
import com.nexa.api.bootstrap.runtime.boundaries.TenantBoundCatalogDetailReader;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSortField;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.SortDirection;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.AuthoritativeOfferQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.service.CatalogQueryService;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcAuthoritativeOfferQuery;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcCatalogItemQueryAdapter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcSellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.infrastructure.query.JdbcTenantCatalogDetailQueryFactory;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogFamilySkuMappingLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceSeedLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogSkuPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogVariantMappingLoader;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.ClientAccountPersistenceAdapter;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import com.nexa.api.inventoryavailability.infrastructure.persistence.CatalogProductAvailabilityAdapter;
import com.nexa.api.inventoryavailability.infrastructure.persistence.JdbcTenantCatalogAvailabilityAdapterFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.VerifiedMembershipResolutionPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service.ResolveCurrentAccessContextService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rehearses opt-in catalog reads against two extracted Tenant fixture databases. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantBoundCatalogDetailReaderIT {
	private static final String ADMIN_USER = "nexa_admin";
	private static final String ADMIN_PASSWORD = "bootstrap-admin-test-password";
	private static final String CENTRAL_RUNTIME_PASSWORD = "central-runtime-test-password";
	private static final String MIGRATOR_PASSWORD = "tenant-migrator-test-password";
	private static final String RUNTIME_PASSWORD_A = "tenant-runtime-a-test-password";
	private static final String RUNTIME_PASSWORD_B = "tenant-runtime-b-test-password";
	private static final String POLICY_WRITER_PASSWORD = "tenant-policy-writer-test-password";
	private static final String CATALOG_ITEM_ID = "CAT-0001";
	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();

	@Container
	private static final PostgreSQLContainer CENTRAL = container("nexa-catalog-route-central");
	@Container
	private static final PostgreSQLContainer TENANT_A = container("nexa-catalog-route-tenant-a");
	@Container
	private static final PostgreSQLContainer TENANT_B = container("nexa-catalog-route-tenant-b");

	private static JdbcTemplate centralAdmin;
	private static TenantFixture tenantA;
	private static TenantFixture tenantB;
	private static CatalogItemDetail expectedA;
	private static CatalogItemDetail expectedB;

	@BeforeAll
	static void prepareDatabasesAndExtractCatalogFixtures() throws Exception {
		configureCentralRuntime(CENTRAL, CENTRAL_RUNTIME_PASSWORD);
		migrateCentral();
		provisionTenantRoles(TENANT_A, RUNTIME_PASSWORD_A);
		provisionTenantRoles(TENANT_B, RUNTIME_PASSWORD_B);
		migrateTenant(TENANT_A);
		migrateTenant(TENANT_B);
		centralAdmin = guardedCentralAdmin(CENTRAL);

		tenantA = seedCentralAuthority("A", TENANT_A, RUNTIME_PASSWORD_A, new java.math.BigDecimal("11.25"),
				new java.math.BigDecimal("214"));
		tenantB = seedCentralAuthority("B", TENANT_B, RUNTIME_PASSWORD_B, new java.math.BigDecimal("13.50"),
				new java.math.BigDecimal("137"));
		seedReviewedCatalog(CENTRAL, List.of(tenantA, tenantB));
		seedBuyerAccountAndTerms(tenantA);
		seedBuyerAccountAndTerms(tenantB);
		seedInventoryFixture(tenantA);
		seedInventoryFixture(tenantB);

		expectedA = readCentralFixture(tenantA);
		expectedB = readCentralFixture(tenantB);
		assertThat(expectedA.pricing().effectivePrice()).isEqualByComparingTo("11.25");
		assertThat(expectedB.pricing().effectivePrice()).isEqualByComparingTo("13.50");
		assertThat(expectedA.sellableAvailability()).isEqualByComparingTo("214");
		assertThat(expectedB.sellableAvailability()).isEqualByComparingTo("137");

		seedLocalScope(TENANT_A, tenantA);
		seedLocalScope(TENANT_B, tenantB);
		List<String> catalogTables = TenantBusinessDatabaseBaselineGenerator.readManifest(REPOSITORY_ROOT).stream()
				.filter(table -> table.owner().equals("BC03"))
				.map(TenantBusinessDatabaseBaselineGenerator.Table::name).toList();
		copyAndVerifyScope(CENTRAL, TENANT_A, catalogTables, tenantA);
		copyAndVerifyScope(CENTRAL, TENANT_B, catalogTables, tenantB);
		List<String> accountTables = List.of("sales.client_account", "sales.client_account_membership");
		copyAndVerifyScope(CENTRAL, TENANT_A, accountTables, tenantA);
		copyAndVerifyScope(CENTRAL, TENANT_B, accountTables, tenantB);
		List<String> inventoryTables = List.of("warehouse.warehouse", "warehouse.storage_zone", "warehouse.inventory_lot");
		copyAndVerifyScope(CENTRAL, TENANT_A, inventoryTables, tenantA);
		copyAndVerifyScope(CENTRAL, TENANT_B, inventoryTables, tenantB);

		// Once extraction is checked, the central fixture no longer contains BC-02
		// Buyer relationships. The routed read must obtain them only from each
		// Tenant database; central IAM/membership authority remains intact.
		centralAdmin.update("DELETE FROM sales.client_account_membership WHERE tenant_id IN (?, ?)",
			tenantA.tenantId().value(), tenantB.tenantId().value());
		centralAdmin.update("DELETE FROM sales.client_account WHERE id IN (?, ?)",
			tenantA.accountId(), tenantB.accountId());
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void buyerCatalogReadResolvesRelationshipOffersAndInventoryOnlyInsideItsVerifiedTenantDatabase() {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				"buyer", "test", List.of(new SimpleGrantedAuthority("catalog:read"))));

		ResolveCurrentAccessContextUseCase accessContexts = accessContexts(centralAdmin);
		CurrentAccessContext accessA = resolveContext(accessContexts, tenantA);
		CurrentAccessContext accessB = resolveContext(accessContexts, tenantB);
		Map<String, TenantBusinessDatabaseCredentials> credentials = Map.of(
			tenantA.credentialReference(), credentials(TENANT_A, RUNTIME_PASSWORD_A),
			tenantB.credentialReference(), credentials(TENANT_B, RUNTIME_PASSWORD_B));
		AtomicInteger poolsCreated = new AtomicInteger();
		TenantBusinessDatabaseDataSourceFactory poolFactory = binding -> {
			TenantBusinessDatabaseCredentials credential = credentials.get(binding.credentialSecretReference());
			if (credential == null) throw new IllegalStateException("Missing test Tenant credentials");
			poolsCreated.incrementAndGet();
			com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
			config.setJdbcUrl(credential.jdbcUrl());
			config.setUsername(credential.username());
			config.setPassword(credential.password());
			config.setMaximumPoolSize(2);
			config.setPoolName("tenant-catalog-" + binding.databaseIdentity());
			return new com.zaxxer.hikari.HikariDataSource(config);
		};
		var authority = new JdbcTenantBusinessDatabaseAuthority(accessContexts,
				new JdbcTenantBusinessDatabaseBindingRegistry(centralAdmin));
		AtomicInteger boundAccountQueries = new AtomicInteger();
		List<String> accountQueryDatabases = new ArrayList<>();
		TenantCustomerAccountQueryFactory tenantAccountQueries = tenantJdbc -> {
			boundAccountQueries.incrementAndGet();
			accountQueryDatabases.add(tenantJdbc.queryForObject("select current_database()", String.class));
			return new ClientAccountPersistenceAdapter(tenantJdbc);
		};
		try (TenantBusinessDatabaseRouter router = new TenantBusinessDatabaseRouter(authority, poolFactory, 2)) {
			Clock catalogClock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), java.time.ZoneOffset.UTC);
			TenantBoundCatalogDetailReader reader = new TenantBoundCatalogDetailReader(router, tenantAccountQueries,
					new JdbcTenantCatalogDetailQueryFactory(catalogClock),
					new JdbcTenantCatalogAvailabilityAdapterFactory());

			SecurityContextHolder.getContext().setAuthentication(
					new UsernamePasswordAuthenticationToken("buyer", "test", List.of()));
			assertThatThrownBy(() -> reader.getByCatalogItemId(accessA, CATALOG_ITEM_ID))
					.isInstanceOf(AccessDeniedException.class);
			CatalogSearchCriteria targetItemCriteria = catalogCriteria(CATALOG_ITEM_ID, 0, 1);
			assertThatThrownBy(() -> reader.listCatalogItems(accessA, targetItemCriteria))
					.isInstanceOf(AccessDeniedException.class);
			assertThat(boundAccountQueries).as("Catalog permission is checked before Buyer profile lookup")
				.hasValue(0);
			assertThat(poolsCreated).as("Catalog permission is checked before Tenant pool acquisition").hasValue(0);
			SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
					"buyer", "test", List.of(new SimpleGrantedAuthority("catalog:read"))));

			CurrentAccessContext forgedMembership = withMembershipId(accessA, UUID.randomUUID());
			assertThatThrownBy(() -> reader.getByCatalogItemId(forgedMembership, CATALOG_ITEM_ID))
					.isInstanceOf(AccessDeniedException.class);
			assertThat(boundAccountQueries).as("central membership revalidation rejects before Tenant work")
				.hasValue(0);
			assertThat(poolsCreated).hasValue(0);

			CatalogItemDetail localA = reader.getByCatalogItemId(accessA, CATALOG_ITEM_ID);
			CatalogItemDetail localB = reader.getByCatalogItemId(accessB, CATALOG_ITEM_ID);
			assertParity(expectedA, localA);
			assertParity(expectedB, localB);
			assertThat(localA.pricing().effectivePrice()).isNotEqualByComparingTo(localB.pricing().effectivePrice());
			assertThat(localA.sellableAvailability()).isNotEqualByComparingTo(localB.sellableAvailability());
			assertThat(accountQueryDatabases).containsExactly(TENANT_A.getDatabaseName(), TENANT_B.getDatabaseName());
			assertThat(boundAccountQueries).hasValue(2);
			assertThat(poolsCreated).hasValue(2);
			assertThat(centralAdmin.queryForObject("select count(*) from sales.client_account", Integer.class)).isZero();

			CatalogPage<CatalogItemSummary> pageA = reader.listCatalogItems(accessA, targetItemCriteria);
			CatalogPage<CatalogItemSummary> pageB = reader.listCatalogItems(accessB, targetItemCriteria);
			assertThat(pageA.items()).hasSize(1);
			assertThat(pageB.items()).hasSize(1);
			assertThat(pageA.page()).isZero();
			assertThat(pageA.size()).isEqualTo(1);
			assertThat(pageA.totalItems()).isEqualTo(1);
			assertThat(pageA.totalPages()).isEqualTo(1);
			assertSummaryParity(expectedA, pageA.items().getFirst());
			assertSummaryParity(expectedB, pageB.items().getFirst());
			assertThat(pageA.items().getFirst().pricing().effectivePrice())
					.isNotEqualByComparingTo(pageB.items().getFirst().pricing().effectivePrice());
			assertThat(pageA.items().getFirst().sellableAvailability())
					.isNotEqualByComparingTo(pageB.items().getFirst().sellableAvailability());

			CatalogPage<CatalogItemSummary> firstPage = reader.listCatalogItems(accessA, catalogCriteria("", 0, 1));
			CatalogPage<CatalogItemSummary> secondPage = reader.listCatalogItems(accessA, catalogCriteria("", 1, 1));
			assertThat(firstPage.items()).hasSize(1);
			assertThat(secondPage.items()).hasSize(1);
			assertThat(firstPage.totalItems()).isGreaterThan(1);
			assertThat(secondPage.totalItems()).isEqualTo(firstPage.totalItems());
			assertThat(secondPage.totalPages()).isEqualTo(firstPage.totalPages());
			assertThat(firstPage.items().getFirst().catalogItemId())
					.isNotEqualTo(secondPage.items().getFirst().catalogItemId());
			assertThat(firstPage.items().getFirst().pricing()).isNotNull();
			assertThat(firstPage.items().getFirst().availabilityStatus()).isNotBlank();
			assertThat(accountQueryDatabases.subList(accountQueryDatabases.size() - 4, accountQueryDatabases.size()))
					.containsExactly(TENANT_A.getDatabaseName(), TENANT_B.getDatabaseName(),
							TENANT_A.getDatabaseName(), TENANT_A.getDatabaseName());

			adminJdbc(TENANT_A).update("DELETE FROM sales.client_account_membership WHERE tenant_id=? AND workspace_id=?",
				accessA.tenantId().value(), accessA.workspaceId().value());
			assertThatThrownBy(() -> reader.getByCatalogItemId(accessA, CATALOG_ITEM_ID))
					.isInstanceOf(AccessDeniedException.class);
			assertThat(boundAccountQueries).hasValue(7);
			assertThat(accountQueryDatabases.getLast()).isEqualTo(TENANT_A.getDatabaseName());

			CatalogItemDetail stillLocalB = reader.getByCatalogItemId(accessB, CATALOG_ITEM_ID);
			assertParity(expectedB, stillLocalB);
			assertThat(accountQueryDatabases.getLast()).isEqualTo(TENANT_B.getDatabaseName());
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	private static void configureCentralRuntime(PostgreSQLContainer central, String runtimePassword) throws SQLException {
		try (Connection connection = adminConnection(central); Statement statement = connection.createStatement()) {
			statement.execute("CREATE ROLE nexa_runtime LOGIN PASSWORD '" + runtimePassword
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
		}
	}

	private static void migrateCentral() {
		Flyway.configure().dataSource(CENTRAL.getJdbcUrl(), CENTRAL.getUsername(), CENTRAL.getPassword())
				.locations("classpath:db/migration").load().migrate();
	}

	private static void provisionTenantRoles(PostgreSQLContainer tenant, String runtimePassword) throws SQLException {
		try (Connection connection = adminConnection(tenant); Statement statement = connection.createStatement()) {
			statement.execute("CREATE ROLE nexa_migrator LOGIN PASSWORD '" + MIGRATOR_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_runtime LOGIN PASSWORD '" + runtimePassword
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_policy_snapshot_writer LOGIN PASSWORD '" + POLICY_WRITER_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + tenant.getDatabaseName() + "\" TO nexa_migrator");
			statement.execute("GRANT CONNECT ON DATABASE \"" + tenant.getDatabaseName() + "\" TO nexa_runtime");
			statement.execute("GRANT CONNECT ON DATABASE \"" + tenant.getDatabaseName()
					+ "\" TO nexa_policy_snapshot_writer");
			statement.execute("GRANT CREATE ON SCHEMA public TO nexa_migrator");
		}
	}

	private static void migrateTenant(PostgreSQLContainer tenant) {
		Flyway.configure().dataSource(tenant.getJdbcUrl(), "nexa_migrator", MIGRATOR_PASSWORD)
				.locations("classpath:db/tenant-migration").target("4").load().migrate();
	}

	private static TenantFixture seedCentralAuthority(String suffix, PostgreSQLContainer tenantDatabase,
			String runtimePassword, java.math.BigDecimal effectivePrice, java.math.BigDecimal availableQuantity) {
		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID databaseIdentity = UUID.randomUUID();
		String secretReference = "test/tenant-catalog/" + suffix + "/" + tenantId;
		Timestamp now = Timestamp.from(Instant.now());
		centralAdmin.update("INSERT INTO tenant_management.tenant (id,name,slug,status,created_at,updated_at) VALUES (?,?,?,?,?,?)",
			tenantId, "Catalog fixture Tenant " + suffix, "catalog-fixture-" + suffix.toLowerCase() + "-" + tenantId,
			"ACTIVE", now, now);
		centralAdmin.update("INSERT INTO tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
			workspaceId, tenantId, "Catalog fixture Workspace " + suffix, "primary", "ACTIVE", now, now);
		centralAdmin.update("INSERT INTO iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
			userId, userId + "@example.test", userId + "@example.test", userId.toString(), userId.toString(),
			"Catalog fixture Buyer " + suffix, "en", "ACTIVE", now, now);
		centralAdmin.update("INSERT INTO tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
			membershipId, workspaceId, userId, "BUYER", "ACTIVE", now, now);
		centralAdmin.update("""
				INSERT INTO tenant_management.membership_role_definition
				    (membership_id,tenant_id,workspace_id,role_id,assigned_at)
				SELECT ?,?,?,id,? FROM tenant_management.role_definition
				WHERE tenant_id IS NULL AND code='buyer' AND status='ACTIVE'
				""", membershipId, tenantId, workspaceId, now);
		centralAdmin.update("INSERT INTO tenant_management.membership_authorization_state (membership_id,tenant_id,workspace_id,authorization_version,updated_at) VALUES (?,?,?,0,?) ON CONFLICT (membership_id) DO NOTHING",
			membershipId, tenantId, workspaceId, now);
		centralAdmin.update("INSERT INTO tenant_management.tenant_business_database_binding (tenant_id,database_identity,credential_secret_reference,lifecycle_state) VALUES (?,?,?,'READY')",
			tenantId, databaseIdentity, secretReference);
		return new TenantFixture(new TenantId(tenantId), new WorkspaceId(workspaceId), new MembershipId(membershipId),
				new UserId(userId), accountId, databaseIdentity, secretReference, tenantDatabase.getDatabaseName(),
				tenantDatabase, runtimePassword, effectivePrice, availableQuantity);
	}

	private static void seedReviewedCatalog(PostgreSQLContainer central, List<TenantFixture> tenants) throws Exception {
		JdbcTemplate jdbc = adminJdbc(central);
		WorkspaceDirectory directory = new WorkspaceDirectory() {
			private final List<Scope> scopes = tenants.stream()
					.map(tenant -> new Scope(tenant.tenantId().value(), tenant.workspaceId().value()))
					.sorted(java.util.Comparator.comparing(Scope::tenantId).thenComparing(Scope::workspaceId)).toList();
			@Override public boolean exists(UUID tenantId, UUID workspaceId) {
				return scopes.contains(new Scope(tenantId, workspaceId));
			}
			@Override public List<Scope> scanAfter(UUID tenantId, UUID workspaceId, int limit) {
				return after(tenantId, workspaceId, limit);
			}
			@Override public List<Scope> scanActiveAfter(UUID tenantId, UUID workspaceId, int limit) {
				return after(tenantId, workspaceId, limit);
			}
			private List<Scope> after(UUID tenantId, UUID workspaceId, int limit) {
				return scopes.stream().filter(scope -> tenantId == null || compare(scope, tenantId, workspaceId) > 0)
						.limit(limit).toList();
			}
			private int compare(Scope scope, UUID tenantId, UUID workspaceId) {
				int tenantOrder = scope.tenantId().compareTo(tenantId);
				return tenantOrder == 0 ? scope.workspaceId().compareTo(workspaceId) : tenantOrder;
			}
		};
		JsonMapper mapper = JsonMapper.builder().build();
		new CatalogPersistenceBootstrap(jdbc, new CatalogPersistenceSeedLoader(mapper), directory).importDeterministicSeed();
		new CatalogSkuPersistenceBootstrap(jdbc, new CatalogFamilySkuMappingLoader(mapper),
				new CatalogVariantMappingLoader(mapper), directory).reconcile();
	}

	private static void seedBuyerAccountAndTerms(TenantFixture tenant) {
		Timestamp now = Timestamp.from(Instant.now());
		Timestamp validFrom = Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
		Timestamp validTo = Timestamp.from(Instant.parse("2099-12-31T00:00:00Z"));
		centralAdmin.update("""
				INSERT INTO sales.client_account
				    (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,
				     tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,
				     status,created_at,updated_at,version)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
				""", tenant.accountId(), tenant.tenantId().value(), tenant.workspaceId().value(),
				"BUYER-" + tenant.accountId().toString().substring(0, 8),
				"Fixture Buyer " + tenant.databaseName(), "Fixture Buyer " + tenant.databaseName(), "PE", "RUC",
				"20" + tenant.accountId().toString().replace("-", "").substring(0, 10),
				"SEG-" + tenant.accountId().toString().substring(0, 8),
				"Fixture Contact", tenant.accountId() + "@example.test", "+51999999999", "Fixture delivery profile",
				"NET_30", "ACTIVE", now, now);
		centralAdmin.update("INSERT INTO sales.client_account_membership (client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) VALUES (?,?,?,?,?)",
			tenant.accountId(), tenant.membershipId().value(), tenant.tenantId().value(), tenant.workspaceId().value(), now);

		UUID skuId = centralAdmin.queryForObject("SELECT id FROM catalog_management.sellable_sku WHERE tenant_id=? AND workspace_id=? AND legacy_catalog_item_id=?",
				UUID.class, tenant.tenantId().value(), tenant.workspaceId().value(), CATALOG_ITEM_ID);
		UUID priceListId = UUID.randomUUID();
		centralAdmin.update("INSERT INTO catalog_management.price_list (price_list_id,tenant_id,workspace_id,code,name,currency,status,valid_from,valid_to,created_at,updated_at,version) VALUES (?,?,?,?,?,'PEN','ACTIVE',?,?,?, ?,0)",
			priceListId, tenant.tenantId().value(), tenant.workspaceId().value(), "TEST-" + priceListId,
			"Tenant-specific catalog fixture price", validFrom, validTo, now, now);
		centralAdmin.update("INSERT INTO catalog_management.price_list_item (item_id,tenant_id,workspace_id,price_list_id,sku_id,unit_price,currency,valid_from,valid_to) VALUES (?,?,?,?,?,?,'PEN',?,?)",
			UUID.randomUUID(), tenant.tenantId().value(), tenant.workspaceId().value(), priceListId, skuId,
			tenant.effectivePrice(), validFrom, validTo);
		centralAdmin.update("INSERT INTO catalog_management.customer_terms (terms_id,tenant_id,workspace_id,customer_account_id,price_list_id,credit_days,currency,valid_from,valid_to,created_at,version) VALUES (?,?,?,?,?,30,'PEN',?,?,?,0)",
			UUID.randomUUID(), tenant.tenantId().value(), tenant.workspaceId().value(), tenant.accountId(), priceListId,
			validFrom, validTo, now);
	}

	private static void seedInventoryFixture(TenantFixture tenant) {
		Timestamp now = Timestamp.from(Instant.now());
		UUID skuId = centralAdmin.queryForObject("SELECT id FROM catalog_management.sellable_sku WHERE tenant_id=? AND workspace_id=? AND legacy_catalog_item_id=?",
				UUID.class, tenant.tenantId().value(), tenant.workspaceId().value(), CATALOG_ITEM_ID);
		UUID warehouseId = UUID.randomUUID();
		UUID zoneId = UUID.randomUUID();
		centralAdmin.update("INSERT INTO warehouse.warehouse (id,tenant_id,workspace_id,code,name,status,created_at,updated_at,version) VALUES (?,?,?,?,?,'ACTIVE',?,?,0)",
			warehouseId, tenant.tenantId().value(), tenant.workspaceId().value(), "WH-" + tenant.databaseName(),
			"Tenant fixture warehouse", now, now);
		centralAdmin.update("INSERT INTO warehouse.storage_zone (id,tenant_id,workspace_id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,created_at,updated_at,version) VALUES (?,?,?,?,?,?,'AMBIENT',NULL,NULL,'ACTIVE',?,?,0)",
			zoneId, tenant.tenantId().value(), tenant.workspaceId().value(), warehouseId, "ZONE-A", "Fixture zone", now, now);
		centralAdmin.update("""
				INSERT INTO warehouse.inventory_lot
				    (id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,
				     expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,version)
				VALUES (?,?,?,?,?,?,?,'FIXTURE-BATCH',current_date + 30,?,?,0,'UNIT','AVAILABLE',0)
				""", UUID.randomUUID(), tenant.tenantId().value(), tenant.workspaceId().value(), warehouseId, zoneId,
				CATALOG_ITEM_ID, skuId, now, tenant.availableQuantity());
	}

	private static CatalogItemDetail readCentralFixture(TenantFixture tenant) {
		CustomerAccountQuery customerQuery = new ClientAccountPersistenceAdapter(centralAdmin);
		CatalogClientAccountPort clientAccounts = new CatalogClientAccountCompositionAdapter(customerQuery);
		AuthoritativeOfferQuery offers = new JdbcAuthoritativeOfferQuery(centralAdmin, clientAccounts);
		var sellableSkus = new JdbcSellableSkuQuery(centralAdmin, offers);
		ProductAvailabilityPort availability = new CatalogProductAvailabilityAdapter(centralAdmin, sellableSkus);
		var itemQuery = new JdbcCatalogItemQueryAdapter(centralAdmin, availability, offers,
				Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), java.time.ZoneOffset.UTC));
		var customer = customerQuery.findActiveBuyerDetails(tenant.tenantId().toString(), tenant.workspaceId().toString(),
				tenant.membershipId().toString()).orElseThrow();
		CatalogScope scope = new CatalogScope(tenant.tenantId().value(), tenant.workspaceId().value(), true,
				UUID.fromString(customer.id()), customer.segment(), null);
		return new CatalogQueryService(itemQuery).getByCatalogItemId(scope, CATALOG_ITEM_ID);
	}

	private static void seedLocalScope(PostgreSQLContainer tenantDatabase, TenantFixture tenant) {
		JdbcTemplate admin = adminJdbc(tenantDatabase);
		admin.update("INSERT INTO nexa_platform.tenant_business_database_identity (tenant_id,database_identity) VALUES (?,?)",
			tenant.tenantId().value(), tenant.databaseIdentity());
		admin.update("INSERT INTO nexa_platform.tenant_workspace_scope_anchor (tenant_id,workspace_id) VALUES (?,?)",
			tenant.tenantId().value(), tenant.workspaceId().value());
	}

	private static void copyAndVerifyScope(PostgreSQLContainer source, PostgreSQLContainer target,
			List<String> tables, TenantFixture tenant) throws SQLException {
		List<String> ordered = topologicalTables(source, tables);
		copyTablesForScope(source, target, ordered, tenant);
		assertThat(scopeChecksums(source, tables, tenant)).as("preserved scoped fixture rows for %s", tenant.databaseName())
				.isEqualTo(scopeChecksums(target, tables, tenant));
	}

	private static void copyTablesForScope(PostgreSQLContainer source, PostgreSQLContainer target,
			List<String> tables, TenantFixture tenant) throws SQLException {
		try (Connection sourceConnection = adminConnection(source); Connection targetConnection = adminConnection(target)) {
			targetConnection.setAutoCommit(false);
			try {
				for (String table : tables) {
					List<String> columns = columnNames(sourceConnection, table);
					if (!columns.containsAll(List.of("tenant_id", "workspace_id"))) {
						throw new IllegalStateException("Scoped fixture table lacks Tenant/Workspace columns: " + table);
					}
					List<String> keys = primaryKeyColumns(sourceConnection, table);
					String columnList = columns.stream().map(TenantBoundCatalogDetailReaderIT::quote).collect(Collectors.joining(","));
					String orderBy = keys.stream().map(TenantBoundCatalogDetailReaderIT::quote).collect(Collectors.joining(","));
					String query = "SELECT " + columnList + " FROM " + quoteQualified(table)
							+ " WHERE tenant_id=? AND workspace_id=? ORDER BY " + orderBy;
					String insert = "INSERT INTO " + quoteQualified(table) + " (" + columnList + ") VALUES ("
							+ columns.stream().map(ignored -> "?").collect(Collectors.joining(",")) + ")";
					try (PreparedStatement select = sourceConnection.prepareStatement(query);
							PreparedStatement create = targetConnection.prepareStatement(insert)) {
						select.setObject(1, tenant.tenantId().value());
						select.setObject(2, tenant.workspaceId().value());
						try (ResultSet rows = select.executeQuery()) {
							while (rows.next()) {
								for (int index = 1; index <= columns.size(); index++) create.setObject(index, rows.getObject(index));
								create.addBatch();
							}
						}
						create.executeBatch();
					}
				}
				targetConnection.commit();
			} catch (SQLException | RuntimeException exception) {
				targetConnection.rollback();
				throw exception;
			}
		}
	}

	private static List<String> topologicalTables(PostgreSQLContainer source, List<String> tables) throws SQLException {
		Set<String> selected = new LinkedHashSet<>(tables);
		Map<String, Set<String>> dependencies = new LinkedHashMap<>();
		for (String table : selected) dependencies.put(table, new LinkedHashSet<>());
		try (Connection connection = adminConnection(source); Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
					SELECT source_ns.nspname || '.' || source_table.relname,
					       target_ns.nspname || '.' || target_table.relname
					FROM pg_constraint c
					JOIN pg_class source_table ON source_table.oid=c.conrelid
					JOIN pg_namespace source_ns ON source_ns.oid=source_table.relnamespace
					JOIN pg_class target_table ON target_table.oid=c.confrelid
					JOIN pg_namespace target_ns ON target_ns.oid=target_table.relnamespace
					WHERE c.contype='f'
					""")) {
			while (rows.next()) {
				String child = rows.getString(1), parent = rows.getString(2);
				if (selected.contains(child) && selected.contains(parent) && !child.equals(parent)) {
					dependencies.get(child).add(parent);
				}
			}
		}
		List<String> ordered = new ArrayList<>();
		Set<String> remaining = new TreeSet<>(selected);
		while (!remaining.isEmpty()) {
			List<String> ready = remaining.stream()
					.filter(table -> dependencies.get(table).stream().noneMatch(remaining::contains)).toList();
			if (ready.isEmpty()) throw new IllegalStateException("Scoped fixture tables contain a foreign-key cycle: " + remaining);
			ordered.addAll(ready);
			remaining.removeAll(ready);
		}
		return ordered;
	}

	private static Map<String, String> scopeChecksums(PostgreSQLContainer database, List<String> tables,
			TenantFixture tenant) throws SQLException {
		Map<String, String> result = new LinkedHashMap<>();
		try (Connection connection = adminConnection(database)) {
			for (String table : tables) {
				List<String> keys = primaryKeyColumns(connection, table);
				String order = keys.stream().map(TenantBoundCatalogDetailReaderIT::quote).collect(Collectors.joining(","));
				String sql = "SELECT to_jsonb(row_data)::text FROM " + quoteQualified(table)
						+ " row_data WHERE tenant_id=? AND workspace_id=? ORDER BY " + order;
				StringBuilder canonical = new StringBuilder();
				try (PreparedStatement query = connection.prepareStatement(sql)) {
					query.setObject(1, tenant.tenantId().value());
					query.setObject(2, tenant.workspaceId().value());
					try (ResultSet rows = query.executeQuery()) {
						while (rows.next()) {
							String row = rows.getString(1);
							canonical.append(row.length()).append(':').append(row).append('\n');
						}
					}
				}
				result.put(table, sha256(canonical.toString()));
			}
		}
		return result;
	}

	private static List<String> columnNames(Connection connection, String table) throws SQLException {
		String[] parts = table.split("\\.", 2);
		List<String> columns = new ArrayList<>();
		try (ResultSet rows = connection.getMetaData().getColumns(null, parts[0], parts[1], null)) {
			while (rows.next()) columns.add(rows.getString("COLUMN_NAME"));
		}
		return columns;
	}

	private static List<String> primaryKeyColumns(Connection connection, String table) throws SQLException {
		String[] parts = table.split("\\.", 2);
		Map<Short, String> keys = new java.util.TreeMap<>();
		try (ResultSet rows = connection.getMetaData().getPrimaryKeys(null, parts[0], parts[1])) {
			while (rows.next()) keys.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
		}
		return List.copyOf(keys.values());
	}

	private static ResolveCurrentAccessContextUseCase accessContexts(JdbcTemplate centralJdbc) {
		VerifiedMembershipResolutionPort resolution = (userId, tenantId, workspaceId) -> centralJdbc.query("""
				SELECT m.id,m.user_id,w.tenant_id,m.workspace_id,m.status,s.authorization_version,
				       t.status tenant_status,w.status workspace_status,r.id role_definition_id,
				       r.code role_code,rp.permission_key
				FROM tenant_management.workspace_membership m
				JOIN tenant_management.workspace w ON w.id=m.workspace_id
				JOIN tenant_management.tenant t ON t.id=w.tenant_id
				LEFT JOIN tenant_management.membership_authorization_state s ON s.membership_id=m.id
				LEFT JOIN tenant_management.membership_role_definition a ON a.membership_id=m.id
				LEFT JOIN tenant_management.role_definition r ON r.id=a.role_id AND r.status='ACTIVE'
				LEFT JOIN tenant_management.role_permission rp ON rp.role_id=r.id
				WHERE m.user_id=? AND w.tenant_id=? AND m.workspace_id=?
				ORDER BY r.id,rp.permission_key
				""", rows -> {
				if (!rows.next()) return Optional.empty();
				UUID membershipId = rows.getObject("id", UUID.class);
				UUID resolvedUser = rows.getObject("user_id", UUID.class);
				UUID resolvedTenant = rows.getObject("tenant_id", UUID.class);
				UUID resolvedWorkspace = rows.getObject("workspace_id", UUID.class);
				String membershipStatus = rows.getString("status");
				String tenantStatus = rows.getString("tenant_status");
				String workspaceStatus = rows.getString("workspace_status");
				long authorizationVersion = rows.getLong("authorization_version");
				Set<MembershipRole> roles = new LinkedHashSet<>();
				Set<String> roleCodes = new LinkedHashSet<>();
				Set<String> roleIds = new LinkedHashSet<>();
				Set<String> permissions = new LinkedHashSet<>();
				do {
					String roleCode = rows.getString("role_code");
					UUID roleId = rows.getObject("role_definition_id", UUID.class);
					if (roleCode != null && roleId != null) {
						roles.add(MembershipRole.valueOf(roleCode.toUpperCase(java.util.Locale.ROOT)));
						roleCodes.add(roleCode.toUpperCase(java.util.Locale.ROOT));
						roleIds.add(roleId.toString());
					}
					String permission = rows.getString("permission_key");
					if (permission != null) permissions.add(permission);
				} while (rows.next());
				Membership membership = new Membership(new MembershipId(membershipId), new UserId(resolvedUser),
						new TenantId(resolvedTenant), new WorkspaceId(resolvedWorkspace), roles, roleCodes, roleIds,
						MembershipStatus.from(membershipStatus), authorizationVersion);
				EffectiveAuthorization authorization = new EffectiveAuthorization(roleIds, roleCodes, permissions,
					authorizationVersion);
				return Optional.of(new VerifiedMembership(membership, TenantStatus.from(tenantStatus),
						WorkspaceStatus.from(workspaceStatus), authorization));
			}, userId.value(), tenantId.value(), workspaceId.value());
		return new ResolveCurrentAccessContextService(resolution);
	}

	private static CurrentAccessContext resolveContext(ResolveCurrentAccessContextUseCase accessContexts,
			TenantFixture tenant) {
		return accessContexts.resolve(new CurrentAccessRequest(tenant.userId(), tenant.tenantId(),
				tenant.workspaceId(), Surface.PORTAL));
	}

	private static CurrentAccessContext withMembershipId(CurrentAccessContext original, UUID membershipId) {
		Membership previous = original.verifiedMembership().membership();
		Membership forgedMembership = new Membership(new MembershipId(membershipId), previous.userId(),
				previous.tenantId(), previous.workspaceId(), previous.roles(), previous.roleCodes(),
				previous.roleDefinitionIds(), previous.status(), previous.authorizationVersion());
		VerifiedMembership verified = new VerifiedMembership(forgedMembership,
				original.verifiedMembership().tenantStatus(), original.verifiedMembership().workspaceStatus(),
				original.verifiedMembership().authorization());
		return CurrentAccessContext.from(verified, original.surface());
	}

	private static void assertParity(CatalogItemDetail expected, CatalogItemDetail actual) {
		assertThat(actual.catalogItemId()).isEqualTo(expected.catalogItemId());
		assertThat(actual.itemName()).isEqualTo(expected.itemName());
		assertThat(actual.productFamilyCode()).isEqualTo(expected.productFamilyCode());
		assertThat(actual.sellableSkuId()).isEqualTo(expected.sellableSkuId());
		assertThat(actual.pricing()).isNotNull();
		assertThat(actual.pricing().buyerView()).isTrue();
		assertThat(actual.pricing().basePrice()).isEqualByComparingTo(expected.pricing().basePrice());
		assertThat(actual.pricing().effectivePrice()).isEqualByComparingTo(expected.pricing().effectivePrice());
		assertThat(actual.pricing().currency()).isEqualTo(expected.pricing().currency());
		assertThat(actual.availabilityStatus()).isEqualTo(expected.availabilityStatus());
		assertThat(actual.sellableAvailability()).isEqualByComparingTo(expected.sellableAvailability());
	}

	private static void assertSummaryParity(CatalogItemDetail expected, CatalogItemSummary actual) {
		assertThat(actual.catalogItemId()).isEqualTo(expected.catalogItemId());
		assertThat(actual.itemName()).isEqualTo(expected.itemName());
		assertThat(actual.productFamilyCode()).isEqualTo(expected.productFamilyCode());
		assertThat(actual.sellableSkuId()).isEqualTo(expected.sellableSkuId());
		assertThat(actual.pricing()).isNotNull();
		assertThat(actual.pricing().buyerView()).isTrue();
		assertThat(actual.pricing().basePrice()).isEqualByComparingTo(expected.pricing().basePrice());
		assertThat(actual.pricing().effectivePrice()).isEqualByComparingTo(expected.pricing().effectivePrice());
		assertThat(actual.pricing().currency()).isEqualTo(expected.pricing().currency());
		assertThat(actual.availabilityStatus()).isEqualTo(expected.availabilityStatus());
		assertThat(actual.sellableAvailability()).isEqualByComparingTo(expected.sellableAvailability());
	}

	private static CatalogSearchCriteria catalogCriteria(String query, int page, int size) {
		return new CatalogSearchCriteria(query, null, null, null, page, size,
				CatalogSortField.ITEM_NAME, SortDirection.ASC);
	}

	private static TenantBusinessDatabaseCredentials credentials(PostgreSQLContainer tenant, String password) {
		return new TenantBusinessDatabaseCredentials(tenant.getJdbcUrl(), "nexa_runtime", password);
	}

	private static JdbcTemplate adminJdbc(PostgreSQLContainer tenant) {
		DriverManagerDataSource source = new DriverManagerDataSource();
		source.setUrl(tenant.getJdbcUrl());
		source.setUsername(tenant.getUsername());
		source.setPassword(tenant.getPassword());
		return new JdbcTemplate(source);
	}

	private static JdbcTemplate guardedCentralAdmin(PostgreSQLContainer central) {
		return new JdbcTemplate(new RlsScopedDataSource(adminJdbc(central).getDataSource()));
	}

	private static Connection adminConnection(PostgreSQLContainer tenant) throws SQLException {
		return DriverManager.getConnection(tenant.getJdbcUrl(), tenant.getUsername(), tenant.getPassword());
	}

	private static PostgreSQLContainer container(String databaseName) {
		return new PostgreSQLContainer("postgres:18.4-alpine").withDatabaseName(databaseName)
				.withUsername(ADMIN_USER).withPassword(ADMIN_PASSWORD);
	}

	private static String quoteQualified(String name) {
		String[] parts = name.split("\\.", 2);
		return quote(parts[0]) + "." + quote(parts[1]);
	}

	private static String quote(String value) {
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record TenantFixture(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId,
			UserId userId, UUID accountId, UUID databaseIdentity, String credentialReference, String databaseName,
			PostgreSQLContainer database, String runtimePassword, java.math.BigDecimal effectivePrice,
			java.math.BigDecimal availableQuantity) { }
}
