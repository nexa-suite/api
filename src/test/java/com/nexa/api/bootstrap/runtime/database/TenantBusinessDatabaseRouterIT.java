package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.support.CentralMigrationTestBootstrap;
import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogFamilySkuMappingLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceSeedItemRecord;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceSeedLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceSeedValidator;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception.InaccessibleTenantException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.VerifiedMembershipResolutionPort;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Exercises opt-in routing against one central and two physically separate PostgreSQL databases. */
@Testcontainers(disabledWithoutDocker = true)
class TenantBusinessDatabaseRouterIT {
	private static final String CENTRAL_RUNTIME_PASSWORD = CentralMigrationTestBootstrap.RUNTIME_PASSWORD;
	private static final String TENANT_A_RUNTIME_PASSWORD = "tenant-a-runtime-only-test-password";
	private static final String TENANT_B_RUNTIME_PASSWORD = "tenant-b-runtime-only-test-password";
	private static final String TENANT_POLICY_SNAPSHOT_WRITER_PASSWORD = "tenant-policy-snapshot-writer-test-password";
	private static final String DOCUMENTS_WORKER_PASSWORD = "tenant-documents-worker-only-test-password";
	private static final String PAYMENTS_WORKER_PASSWORD = "tenant-payments-worker-only-test-password";
	private static final String TRACEABILITY_WORKER_PASSWORD = "tenant-traceability-worker-only-test-password";
	private static final String RUNTIME_USERNAME = "nexa_runtime";

	@Container
	private static final PostgreSQLContainer CENTRAL = container("nexa-central");
	@Container
	private static final PostgreSQLContainer TENANT_A = container("nexa-tenant-a");
	@Container
	private static final PostgreSQLContainer TENANT_B = container("nexa-tenant-b");

	private JdbcTemplate centralAdmin;
	private JdbcTemplate centralRuntime;
	private JdbcTemplate tenantAAdmin;
	private JdbcTemplate tenantBAdmin;
	private static TestFixture fixture;

	@BeforeAll
	static void migrateDatabases() {
		configureRuntimeRole(CENTRAL, CENTRAL_RUNTIME_PASSWORD);
		configureRuntimeRole(TENANT_A, TENANT_A_RUNTIME_PASSWORD);
		configureRuntimeRole(TENANT_B, TENANT_B_RUNTIME_PASSWORD);
		configurePolicySnapshotWriterRole(TENANT_A);
		configurePolicySnapshotWriterRole(TENANT_B);
		configureWorkerRoles(TENANT_A);
		configureWorkerRoles(TENANT_B);

		CentralMigrationTestBootstrap.migrateToCurrent(CENTRAL);
		Flyway.configure().dataSource(TENANT_A.getJdbcUrl(), TENANT_A.getUsername(), TENANT_A.getPassword())
				.locations("classpath:db/tenant-migration").load().migrate();
		Flyway.configure().dataSource(TENANT_B.getJdbcUrl(), TENANT_B.getUsername(), TENANT_B.getPassword())
				.locations("classpath:db/tenant-migration").load().migrate();

		prepareProbeTable(TENANT_A);
		prepareProbeTable(TENANT_B);
		fixture = seedFixture(adminJdbc(CENTRAL), adminJdbc(TENANT_A), adminJdbc(TENANT_B));
	}

	@BeforeEach
	void restoreCentralFixture() {
		centralAdmin = adminJdbc(CENTRAL);
		centralRuntime = new JdbcTemplate(new RlsScopedDataSource(runtimeDataSource(CENTRAL, CENTRAL_RUNTIME_PASSWORD)));
		tenantAAdmin = adminJdbc(TENANT_A);
		tenantBAdmin = adminJdbc(TENANT_B);
		for (TenantRecord tenant : List.of(fixture.tenantA(), fixture.tenantB())) {
			centralAdmin.update("UPDATE tenant_management.workspace_membership SET status='ACTIVE' WHERE id=?",
					tenant.membershipId().value());
			centralAdmin.update("""
					INSERT INTO tenant_management.tenant_business_database_binding
					    (tenant_id,database_identity,credential_secret_reference,lifecycle_state)
					VALUES (?,?,?,'READY')
					ON CONFLICT (tenant_id) DO UPDATE SET database_identity=EXCLUDED.database_identity,
					    credential_secret_reference=EXCLUDED.credential_secret_reference,
					    lifecycle_state='READY', updated_at=current_timestamp
					""", tenant.tenantId().value(), tenant.databaseIdentity(), tenant.credentialSecretReference());
		}
		tenantAAdmin.update("DELETE FROM nexa_platform.route_probe");
		tenantBAdmin.update("DELETE FROM nexa_platform.route_probe");
	}

	@AfterEach
	void clearRequestScope() {
		RlsRequestScope.clear();
	}

	@Test
	void tenantRuntimeCannotMutateChangeFeedBySettingMaintenanceGuc() throws Exception {
		try (Connection runtime = DriverManager.getConnection(TENANT_A.getJdbcUrl(), RUNTIME_USERNAME,
				TENANT_A_RUNTIME_PASSWORD);
			 Statement statement = runtime.createStatement()) {
			statement.execute("SELECT set_config('nexa.change_feed.maintenance', 'on', false)");
			UUID eventId = UUID.randomUUID();
			TenantRecord tenant = fixture.tenantA();
			assertThat(changeEventCount(statement, eventId)).as("Tenant feed must fail closed without scope").isZero();
			setDatabaseSessionScope(runtime, UUID.randomUUID(), UUID.randomUUID());
			assertThatThrownBy(() -> insertChangeEvent(runtime, eventId, tenant))
					.as("Tenant runtime cannot append another Tenant's event")
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("row-level security");
			setDatabaseSessionScope(runtime, tenant.tenantId().value(), tenant.workspaceId().value());
			insertChangeEvent(runtime, eventId, tenant);
			assertThat(changeEventCount(statement, eventId)).as("matching Tenant scope can read its event").isEqualTo(1);
			setDatabaseSessionScope(runtime, UUID.randomUUID(), UUID.randomUUID());
			assertThat(changeEventCount(statement, eventId)).as("foreign scope cannot read Tenant events").isZero();
			setDatabaseSessionScope(runtime, tenant.tenantId().value(), tenant.workspaceId().value());

			assertThatThrownBy(() -> statement.executeUpdate(
					"UPDATE integration.change_event SET event_type = event_type WHERE false"))
					.as("Tenant runtime must not update append-only change events")
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> statement.executeUpdate(
					"DELETE FROM integration.change_event WHERE false"))
					.as("Tenant runtime must use the bounded retention function")
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> statement.execute("TRUNCATE integration.change_event"))
					.as("Tenant runtime must not bypass append-only triggers through TRUNCATE")
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("permission denied");

			try (var result = statement.executeQuery("""
					SELECT has_table_privilege(current_user, 'integration.change_event', 'SELECT'),
					       has_table_privilege(current_user, 'integration.change_event', 'INSERT'),
					       has_function_privilege(current_user,
					           'integration.purge_expired_change_events(integer)', 'EXECUTE')
					""")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getBoolean(1)).isTrue();
				assertThat(result.getBoolean(2)).isTrue();
				assertThat(result.getBoolean(3)).isTrue();
			}
			try (var retention = statement.executeQuery("SELECT integration.purge_expired_change_events(1000)")) {
				assertThat(retention.next()).isTrue();
				assertThat(retention.getLong(1)).as("bounded owner retention still removes expired feed rows")
						.isGreaterThanOrEqualTo(1);
			}
		}
	}

	@Test
	void verifiedCentralBindingRoutesToTwoPhysicalDatabasesAndReusesTenantPools() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CurrentAccessContext tenantB = resolveContext(fixture.tenantB());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			insertProbe(router, tenantA, "tenant-a-first");
			assertThat(probeCount(router, tenantA)).isEqualTo(1);

			setRequestScope(fixture.tenantB());
			insertProbe(router, tenantB, "tenant-b-first");
			assertThat(probeCount(router, tenantB)).isEqualTo(1);

			setRequestScope(fixture.tenantA());
			insertProbe(router, tenantA, "tenant-a-second");
			assertThat(probeCount(router, tenantA)).isEqualTo(2);
			assertThat(pools.createdCount()).as("one pool is reused for each Tenant binding").isEqualTo(2);
			assertThat(pools.createdSecrets()).containsExactlyInAnyOrder(fixture.tenantA().credentialSecretReference(),
					fixture.tenantB().credentialSecretReference());
		}

		assertThat(countProbe(tenantAAdmin, "tenant-a-first")).isEqualTo(1);
		assertThat(countProbe(tenantAAdmin, "tenant-a-second")).isEqualTo(1);
		assertThat(countProbe(tenantBAdmin, "tenant-b-first")).isEqualTo(1);
		assertThat(countProbe(tenantBAdmin, "tenant-a-first")).isZero();
		assertThatThrownBy(() -> DriverManager.getConnection(TENANT_A.getJdbcUrl(), RUNTIME_USERNAME,
				CENTRAL_RUNTIME_PASSWORD)).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> DriverManager.getConnection(CENTRAL.getJdbcUrl(), RUNTIME_USERNAME,
				TENANT_A_RUNTIME_PASSWORD)).isInstanceOf(SQLException.class);
	}

	@Test
	void physicalTenantBusinessChildrenUseForcedParentScopeRls() throws SQLException {
		Map<String, String> protectedChildren = Map.of(
				"sales.purchase_request_line", "purchase_request_line_parent_scope",
				"sales.sales_order_line", "sales_order_line_parent_scope",
				"warehouse.inventory_reservation_line", "inventory_reservation_line_parent_scope",
				"warehouse.inventory_reservation_allocation", "inventory_reservation_allocation_parent_scope",
				"warehouse.reservation_shortage", "reservation_shortage_parent_scope",
				"tenant_management.notification_preference", "notification_preference_parent_scope");
		assertForcedParentRls(tenantAAdmin, protectedChildren);
		assertForcedParentRls(tenantBAdmin, protectedChildren);

		TenantRecord tenantA = fixture.tenantA();
		TenantRecord tenantB = fixture.tenantB();
		try (Connection connection = DriverManager.getConnection(tenantA.container().getJdbcUrl(),
				tenantA.container().getUsername(), tenantA.container().getPassword())) {
			connection.setAutoCommit(false);
			try {
				ParentScopedChildren children = seedParentScopedChildren(connection, tenantA);
				insertFixtureRow(connection, """
						INSERT INTO tenant_management.notification_preference
						    (workspace_id,event_category,channel,enabled,updated_at)
						VALUES (?,'ORDER_STATUS','IN_APP',true,current_timestamp)
						""", tenantA.workspaceId().value());

				// These BEFORE triggers query the parent rows as the runtime caller. With a mismatched
				// workspace they raise a domain exception before PostgreSQL evaluates RLS WITH CHECK.
				// Disable only those two validations in this rollback-only fixture transaction so each
				// rejected INSERT demonstrates the V26 policy itself. RLS/FORCE remains enabled.
				try (Statement statement = connection.createStatement()) {
					statement.execute("ALTER TABLE warehouse.inventory_reservation_line DISABLE TRIGGER reservation_line_sku_scope");
					statement.execute("ALTER TABLE warehouse.inventory_reservation_allocation DISABLE TRIGGER reservation_allocation_scope");
					statement.execute("SET LOCAL ROLE nexa_runtime");
				}
				assertThat(currentDatabaseRole(connection)).isEqualTo(RUNTIME_USERNAME);
				for (String table : protectedChildren.keySet()) {
					assertRuntimeCanSelectAndInsert(connection, table);
				}

				setDatabaseScope(connection, tenantA);
				assertChildCount(connection, "sales.purchase_request_line", children.purchaseRequestLineId(), 1);
				assertChildCount(connection, "sales.sales_order_line", children.salesOrderLineId(), 1);
				assertChildCount(connection, "warehouse.inventory_reservation_line", children.reservationLineId(), 1);
				assertChildCount(connection, "warehouse.inventory_reservation_allocation", children.allocationId(), 1);
				assertChildCount(connection, "warehouse.reservation_shortage", children.shortageId(), 1);
				assertNotificationPreferenceCount(connection, tenantA.workspaceId().value(), 1);

				setDatabaseScope(connection, tenantA.tenantId().value(), tenantB.workspaceId().value());
				assertChildCount(connection, "sales.purchase_request_line", children.purchaseRequestLineId(), 0);
				assertChildCount(connection, "sales.sales_order_line", children.salesOrderLineId(), 0);
				assertChildCount(connection, "warehouse.inventory_reservation_line", children.reservationLineId(), 0);
				assertChildCount(connection, "warehouse.inventory_reservation_allocation", children.allocationId(), 0);
				assertChildCount(connection, "warehouse.reservation_shortage", children.shortageId(), 0);
				assertNotificationPreferenceCount(connection, tenantA.workspaceId().value(), 0);

				assertRlsRejectedInsert(connection, "sales.purchase_request_line", """
					INSERT INTO sales.purchase_request_line
					    (id,purchase_request_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,
					     unit_price_amount,unit_price_currency,created_at,updated_at,sku_id,product_family_id,
					     product_family_code_snapshot,sku_code_snapshot)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
					""", UUID.randomUUID(), children.purchaseRequestId(), children.deniedPurchaseRequestCatalogItemId(),
					"Unauthorized item", "Unit", new BigDecimal("1"), "UNIT", new BigDecimal("1"), "PEN",
					children.createdAt(), children.createdAt(), children.skuId(), children.productFamilyId(),
					children.productFamilyCode(), children.skuCode());
				assertRlsRejectedInsert(connection, "sales.sales_order_line", """
					INSERT INTO sales.sales_order_line
					    (id,sales_order_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,
					     unit_price_amount,unit_price_currency,line_subtotal,created_at,sku_id,product_family_id,
					     product_family_code_snapshot,sku_code_snapshot)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
					""", UUID.randomUUID(), children.salesOrderId(), children.deniedSalesOrderCatalogItemId(),
					"Unauthorized item", "Unit", new BigDecimal("1"), "UNIT", new BigDecimal("1"), "PEN",
					new BigDecimal("1"), children.createdAt(), children.skuId(), children.productFamilyId(),
					children.productFamilyCode(), children.skuCode());
				assertRlsRejectedInsert(connection, "warehouse.inventory_reservation_line", """
					INSERT INTO warehouse.inventory_reservation_line
					    (id,reservation_id,catalog_item_id,requested_quantity,unit,shortage_quantity,sku_id)
					VALUES (?,?,?,?,?,?,?)
					""", UUID.randomUUID(), children.reservationId(), children.deniedReservationCatalogItemId(),
					new BigDecimal("1"), "UNIT", BigDecimal.ZERO, children.alternateSkuId());
				assertRlsRejectedInsert(connection, "warehouse.inventory_reservation_allocation", """
					INSERT INTO warehouse.inventory_reservation_allocation
					    (id,reservation_line_id,lot_id,quantity,unit,expiration_date)
					VALUES (?,?,?,?,?,?)
					""", UUID.randomUUID(), children.reservationLineId(), children.alternateLotId(),
					new BigDecimal("1"), "UNIT", children.expirationDate());
				assertRlsRejectedInsert(connection, "warehouse.reservation_shortage", """
					INSERT INTO warehouse.reservation_shortage (id,reservation_line_id,quantity,reason)
					VALUES (?,?,?,?)
					""", UUID.randomUUID(), children.reservationLineId(), new BigDecimal("1"), "RLS scope test");
				assertRlsRejectedInsert(connection, "tenant_management.notification_preference", """
					INSERT INTO tenant_management.notification_preference
					    (workspace_id,event_category,channel,enabled,updated_at)
					VALUES (?,'INVITATION','EMAIL',true,current_timestamp)
					""", tenantA.workspaceId().value());
			} finally {
				// This also removes append-only child rows; rollback restores the validation triggers.
				connection.rollback();
			}
		}
	}

	@Test
	void revokedCentralMembershipCannotUseAStaleContextOrCreateABusinessPool() {
		CurrentAccessContext staleContext = resolveContext(fixture.tenantA());
		centralAdmin.update("UPDATE tenant_management.workspace_membership SET status='REVOKED' WHERE id=?",
				fixture.tenantA().membershipId().value());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			assertThatThrownBy(() -> router.inTransaction(staleContext, jdbc -> jdbc.update(
					"INSERT INTO nexa_platform.route_probe (id, tenant_id, workspace_id, marker) VALUES (?, ?, ?, ?)",
					UUID.randomUUID(), staleContext.tenantId().value(), staleContext.workspaceId().value(), "must-not-write")))
					.isInstanceOf(RuntimeException.class);
			assertThat(pools.createdCount()).as("central membership revalidation precedes pool creation").isZero();
		}
		assertThat(countProbe(tenantAAdmin, "must-not-write")).isZero();
	}

	@Test
	void bindingToAnotherTenantsDatabaseIsRejectedBeforeBusinessWorkRuns() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, true));
		AtomicBoolean callbackRan = new AtomicBoolean();

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			assertThatThrownBy(() -> router.inTransaction(tenantA, jdbc -> {
				callbackRan.set(true);
				return jdbc.update("INSERT INTO nexa_platform.route_probe (id, tenant_id, workspace_id, marker) VALUES (?, ?, ?, ?)",
						UUID.randomUUID(), tenantA.tenantId().value(), tenantA.workspaceId().value(), "wrong-physical-database");
			})).hasRootCauseInstanceOf(SQLNonTransientConnectionException.class);
			assertThat(callbackRan).as("the physical identity is checked before business work").isFalse();
			assertThat(pools.createdCount()).isEqualTo(1);
		}

		assertThat(countProbe(tenantAAdmin, "wrong-physical-database")).isZero();
		assertThat(countProbe(tenantBAdmin, "wrong-physical-database")).isZero();
	}

	@Test
	void workspaceAnchorMismatchFailsBeforeBusinessWorkRuns() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		WorkspaceId incorrectWorkspace = WorkspaceId.random();
		tenantAAdmin.update("UPDATE nexa_platform.tenant_workspace_scope_anchor SET workspace_id=? WHERE tenant_id=?",
				incorrectWorkspace.value(), tenantA.tenantId().value());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));
		AtomicBoolean callbackRan = new AtomicBoolean();

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			assertThatThrownBy(() -> router.inTransaction(tenantA, jdbc -> {
				callbackRan.set(true);
				return jdbc.update("INSERT INTO nexa_platform.route_probe (id, tenant_id, workspace_id, marker) VALUES (?, ?, ?, ?)",
						UUID.randomUUID(), tenantA.tenantId().value(), tenantA.workspaceId().value(), "wrong-workspace-anchor");
			})).hasRootCauseInstanceOf(SQLNonTransientConnectionException.class);
			assertThat(callbackRan).as("verified Workspace scope is checked before business work").isFalse();
		} finally {
			tenantAAdmin.update("UPDATE nexa_platform.tenant_workspace_scope_anchor SET workspace_id=? WHERE tenant_id=?",
					fixture.tenantA().workspaceId().value(), fixture.tenantA().tenantId().value());
		}

		assertThat(countProbe(tenantAAdmin, "wrong-workspace-anchor")).isZero();
	}

	@Test
	void missingWorkspaceAnchorFailsBeforeBusinessWorkRuns() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		tenantAAdmin.update("DELETE FROM nexa_platform.tenant_workspace_scope_anchor WHERE tenant_id=?",
				tenantA.tenantId().value());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));
		AtomicBoolean callbackRan = new AtomicBoolean();

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			assertThatThrownBy(() -> router.inTransaction(tenantA, jdbc -> {
				callbackRan.set(true);
				return jdbc.update("INSERT INTO nexa_platform.route_probe (id, tenant_id, workspace_id, marker) VALUES (?, ?, ?, ?)",
						UUID.randomUUID(), tenantA.tenantId().value(), tenantA.workspaceId().value(), "missing-workspace-anchor");
			})).hasRootCauseInstanceOf(SQLNonTransientConnectionException.class);
			assertThat(callbackRan).as("missing local scope metadata fails closed").isFalse();
		} finally {
			tenantAAdmin.update("INSERT INTO nexa_platform.tenant_workspace_scope_anchor (tenant_id,workspace_id) VALUES (?,?)",
					fixture.tenantA().tenantId().value(), fixture.tenantA().workspaceId().value());
		}

		assertThat(countProbe(tenantAAdmin, "missing-workspace-anchor")).isZero();
	}

	@Test
	void tenantRuntimeCanReadButCannotWriteTheWorkspaceScopeAnchor() {
		TenantRecord tenant = fixture.tenantA();
		JdbcTemplate tenantRuntime = new JdbcTemplate(runtimeDataSource(TENANT_A, TENANT_A_RUNTIME_PASSWORD));

		assertThat(tenantRuntime.queryForObject("SELECT count(*) FROM nexa_platform.tenant_workspace_scope_anchor WHERE tenant_id=? AND workspace_id=?",
				Integer.class, tenant.tenantId().value(), tenant.workspaceId().value())).isEqualTo(1);
		Throwable writeFailure = catchThrowable(() -> tenantRuntime.update(
				"UPDATE nexa_platform.tenant_workspace_scope_anchor SET workspace_id=? WHERE tenant_id=?",
				WorkspaceId.random().value(), tenant.tenantId().value()));
		assertThat(writeFailure).isNotNull();
		assertThat(rootCause(writeFailure)).isInstanceOf(SQLException.class);
		assertThat(((SQLException) rootCause(writeFailure)).getSQLState()).isEqualTo("42501");
		assertThat(tenantAAdmin.queryForObject("SELECT count(*) FROM nexa_platform.tenant_workspace_scope_anchor WHERE tenant_id=? AND workspace_id=?",
				Integer.class, tenant.tenantId().value(), tenant.workspaceId().value())).isEqualTo(1);
	}

	@Test
	void optInCatalogReferenceFixturePreservesExplicitMappingsAndIdentifiersAcrossPhysicalDatabases() {
		var objectMapper = JsonMapper.builder().build();
		List<CatalogPersistenceSeedItemRecord> items = new CatalogPersistenceSeedLoader(objectMapper).load();
		Map<String, CatalogFamilySkuMappingLoader.MappingItem> mappings =
				new CatalogFamilySkuMappingLoader(objectMapper).byLegacyCatalogItemId();
		assertThat(items).hasSize(CatalogPersistenceSeedValidator.EXPECTED_COUNT);
		assertThat(mappings).hasSize(CatalogPersistenceSeedValidator.EXPECTED_COUNT);
		assertThat(items.stream().filter(CatalogPersistenceSeedItemRecord::buyerVisible))
				.hasSize(CatalogPersistenceSeedValidator.EXPECTED_CURATED_COUNT);
		assertThat(items.stream().filter(CatalogPersistenceSeedItemRecord::provisionalReference))
				.hasSize(CatalogPersistenceSeedValidator.EXPECTED_PROVISIONAL_COUNT);
		assertThat(mappings.keySet()).containsExactlyInAnyOrderElementsOf(
				items.stream().map(CatalogPersistenceSeedItemRecord::catalogItemId).toList());

		try {
			List<CatalogFixtureProjection> tenantAProjection = provisionCatalogFixture(tenantAAdmin, fixture.tenantA(), items, mappings);
			List<CatalogFixtureProjection> tenantBProjection = provisionCatalogFixture(tenantBAdmin, fixture.tenantB(), items, mappings);

			assertThat(tenantAProjection).hasSize(102).containsExactlyElementsOf(expectedCatalogFixture(fixture.tenantA(), items, mappings));
			assertThat(tenantBProjection).hasSize(102).containsExactlyElementsOf(expectedCatalogFixture(fixture.tenantB(), items, mappings));
			assertThat(tenantAProjection).extracting(CatalogFixtureProjection::productId)
					.doesNotContainAnyElementsOf(tenantBProjection.stream().map(CatalogFixtureProjection::productId).toList());
			assertThat(tenantAAdmin.queryForObject("SELECT count(*) FROM nexa_platform.tenant_workspace_scope_anchor", Integer.class))
					.isEqualTo(1);
			assertThat(tenantBAdmin.queryForObject("SELECT count(*) FROM nexa_platform.tenant_workspace_scope_anchor", Integer.class))
					.isEqualTo(1);
		} finally {
			dropCatalogFixtureProbe(tenantAAdmin);
			dropCatalogFixtureProbe(tenantBAdmin);
		}
	}

	@Test
	void routedTransactionFencesCentralAccessAndNestedTenantRouting() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CurrentAccessContext tenantB = resolveContext(fixture.tenantB());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			UUID rolledBack = UUID.randomUUID();
			assertThatThrownBy(() -> router.inTransaction(tenantA, jdbc -> {
				jdbc.update("INSERT INTO nexa_platform.route_probe (id, tenant_id, workspace_id, marker) VALUES (?, ?, ?, ?)",
						rolledBack, tenantA.tenantId().value(), tenantA.workspaceId().value(), "central-fence-rollback");
				centralRuntime.queryForObject("SELECT 1", Integer.class);
				return null;
			})).hasRootCauseInstanceOf(SQLNonTransientConnectionException.class);
			assertThat(countProbeId(tenantAAdmin, rolledBack)).as("central access failure rolls back business work").isZero();

			UUID nestedRollback = UUID.randomUUID();
			assertThatThrownBy(() -> router.inTransaction(tenantA, jdbc -> {
				jdbc.update("INSERT INTO nexa_platform.route_probe (id, tenant_id, workspace_id, marker) VALUES (?, ?, ?, ?)",
						nestedRollback, tenantA.tenantId().value(), tenantA.workspaceId().value(), "nested-route-rollback");
				try {
					setRequestScope(fixture.tenantB());
					router.inTransaction(tenantB, ignored -> null);
				} finally {
					setRequestScope(fixture.tenantA());
				}
				return null;
			})).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("cannot begin inside another database transaction");
			assertThat(countProbeId(tenantAAdmin, nestedRollback)).as("nested route failure rolls back the outer business transaction").isZero();
			assertThat(pools.createdCount()).as("the rejected nested route creates no second pool").isEqualTo(1);
		}
	}

	@Test
	void absentReadyBindingFailsClosedWithoutCreatingAPool() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		centralAdmin.update("DELETE FROM tenant_management.tenant_business_database_binding WHERE tenant_id=?",
				fixture.tenantA().tenantId().value());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			assertThatThrownBy(() -> router.inTransaction(tenantA, jdbc -> 1))
					.isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
			assertThat(pools.createdCount()).isZero();
		}
	}

	@Test
	void suspendedBindingRetiresItsIdlePoolAndDoesNotReuseIt() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			insertProbe(router, tenantA, "before-suspension");
			assertThat(pools.createdDataSources().getFirst().isClosed()).isFalse();

			centralAdmin.update("UPDATE tenant_management.tenant_business_database_binding SET lifecycle_state='SUSPENDED' WHERE tenant_id=?",
					fixture.tenantA().tenantId().value());
			assertThatThrownBy(() -> insertProbe(router, tenantA, "suspended-binding-denied"))
					.isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
			assertThat(pools.createdDataSources().getFirst().isClosed())
					.as("a suspended Tenant binding retires its idle pool")
					.isTrue();
			assertThat(pools.createdCount()).isEqualTo(1);
		}

		assertThat(countProbe(tenantAAdmin, "before-suspension")).isEqualTo(1);
		assertThat(countProbe(tenantAAdmin, "suspended-binding-denied")).isZero();
	}

	@Test
	void verifiedAccessContextScopesForcedRlsEvenWhenThreadLocalClaimChangesAndClearsPoolState() throws Exception {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			router.inTransaction(tenantA, jdbc -> {
				setRequestScope(fixture.tenantB());
				insertProbe(jdbc, tenantA, "verified-context-rls");
				return null;
			});
			setRequestScope(fixture.tenantA());
			assertThat(probeCount(router, tenantA)).isEqualTo(1);
			assertThat(countProbe(tenantAAdmin, "verified-context-rls")).isEqualTo(1);
			assertThat(countProbe(tenantBAdmin, "verified-context-rls")).isZero();
			try (Connection connection = pools.createdDataSources().getFirst().getConnection();
					var statement = connection.createStatement();
					var result = statement.executeQuery("select current_setting('app.current_tenant_id', true), current_setting('app.current_workspace_id', true)")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getString(1)).isIn(null, "");
				assertThat(result.getString(2)).isIn(null, "");
			}
		}
	}

	@Test
	void poolLimitFailsClosedForActiveLeaseThenEvictsOldestIdlePool() throws Exception {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CurrentAccessContext tenantB = resolveContext(fixture.tenantB());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));
		CountDownLatch tenantAEntered = new CountDownLatch(1);
		CountDownLatch allowTenantAToFinish = new CountDownLatch(1);
		AtomicReference<Throwable> tenantAFailure = new AtomicReference<>();

		try (TenantBusinessDatabaseRouter router = router(pools, 1)) {
			Thread tenantAWorker = new Thread(() -> {
				setRequestScope(fixture.tenantA());
				try {
					router.inTransaction(tenantA, jdbc -> {
						insertProbe(jdbc, tenantA, "active-tenant-a");
						tenantAEntered.countDown();
						await(allowTenantAToFinish);
						return null;
					});
				} catch (Throwable exception) {
					tenantAFailure.set(exception);
				} finally {
					RlsRequestScope.clear();
				}
			}, "tenant-a-active-transaction");
			tenantAWorker.start();
			try {
				assertThat(tenantAEntered.await(10, TimeUnit.SECONDS)).isTrue();
				setRequestScope(fixture.tenantB());
				assertThatThrownBy(() -> insertProbe(router, tenantB, "pool-capacity-denied"))
						.isInstanceOf(TenantBusinessDatabaseUnavailableException.class)
						.hasMessageContaining("capacity is occupied by active transactions");
				assertThat(pools.createdCount()).isEqualTo(1);
				assertThat(pools.createdDataSources().getFirst().isClosed())
						.as("an active transaction's pool is not evicted")
						.isFalse();
			} finally {
				allowTenantAToFinish.countDown();
				tenantAWorker.join(TimeUnit.SECONDS.toMillis(10));
			}
			assertThat(tenantAWorker.isAlive()).isFalse();
			assertThat(tenantAFailure.get()).isNull();

			insertProbe(router, tenantB, "pool-capacity-recovered");
			assertThat(pools.createdCount()).isEqualTo(2);
			assertThat(pools.createdDataSources().getFirst().isClosed())
					.as("the oldest idle pool is closed before a replacement is accepted")
					.isTrue();
		}
	}

	@Test
	void rebindingRetiresOldPoolButLetsItsActiveTransactionFinish() throws Exception {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		String rotatedSecret = fixture.tenantA().credentialSecretReference() + "/rotated";
		Map<String, TenantBusinessDatabaseCredentials> credentials = new java.util.HashMap<>(credentialsFor(fixture, false));
		credentials.put(rotatedSecret, new TenantBusinessDatabaseCredentials(TENANT_A.getJdbcUrl(), RUNTIME_USERNAME,
				TENANT_A_RUNTIME_PASSWORD));
		CountingFactory pools = new CountingFactory(credentials);
		CountDownLatch transactionEntered = new CountDownLatch(1);
		CountDownLatch finishTransaction = new CountDownLatch(1);
		AtomicReference<Throwable> transactionFailure = new AtomicReference<>();

		try (TenantBusinessDatabaseRouter router = router(pools, 1)) {
			Thread worker = new Thread(() -> {
				setRequestScope(fixture.tenantA());
				try {
					router.inTransaction(tenantA, jdbc -> {
						insertProbe(jdbc, tenantA, "pre-rebind-active");
						transactionEntered.countDown();
						await(finishTransaction);
						return null;
					});
				} catch (Throwable exception) {
					transactionFailure.set(exception);
				} finally {
					RlsRequestScope.clear();
				}
			}, "tenant-a-pre-rebind-transaction");
			worker.start();
			try {
				assertThat(transactionEntered.await(10, TimeUnit.SECONDS)).isTrue();
				centralAdmin.update("UPDATE tenant_management.tenant_business_database_binding SET credential_secret_reference=? WHERE tenant_id=?",
						rotatedSecret, fixture.tenantA().tenantId().value());
				setRequestScope(fixture.tenantA());
				assertThatThrownBy(() -> insertProbe(router, tenantA, "retired-binding-denied"))
						.isInstanceOf(TenantBusinessDatabaseUnavailableException.class)
						.hasMessageContaining("capacity is occupied by active transactions");
				assertThat(pools.createdDataSources().getFirst().isClosed()).isFalse();
			} finally {
				finishTransaction.countDown();
				worker.join(TimeUnit.SECONDS.toMillis(10));
			}
			assertThat(worker.isAlive()).isFalse();
			assertThat(transactionFailure.get()).isNull();
			assertThat(pools.createdDataSources().getFirst().isClosed())
					.as("a rebound pool closes only after its in-flight lease is released")
					.isTrue();

			insertProbe(router, tenantA, "post-rebind-active");
			assertThat(countProbe(tenantAAdmin, "pre-rebind-active")).isEqualTo(1);
			assertThat(countProbe(tenantAAdmin, "post-rebind-active")).isEqualTo(1);
			assertThat(countProbe(tenantAAdmin, "retired-binding-denied")).isZero();
		}
	}

	@Test
	void revokedMembershipBetweenTenantSessionPhasesBlocksNextPhaseBeforeWrite() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			router.inTenantSession(tenantA, session -> {
				session.inTransaction(jdbc -> insertProbe(jdbc, tenantA, "phase-before-revocation"));
				centralAdmin.update("UPDATE tenant_management.workspace_membership SET status='REVOKED' WHERE id=?",
						fixture.tenantA().membershipId().value());

				assertThatThrownBy(() -> session.inTransaction(
						jdbc -> insertProbe(jdbc, tenantA, "phase-after-revocation")))
						.isInstanceOf(InaccessibleTenantException.class);
				return null;
			});
		}

		assertThat(countProbe(tenantAAdmin, "phase-before-revocation")).isEqualTo(1);
		assertThat(countProbe(tenantAAdmin, "phase-after-revocation")).isZero();
		assertThat(centralAdmin.queryForObject("SELECT status FROM tenant_management.workspace_membership WHERE id=?",
				String.class, fixture.tenantA().membershipId().value())).isEqualTo("REVOKED");
	}

	@Test
	void rollbackPreservesOriginalBusinessFailureWhenMembershipIsRevokedDuringCallback() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			assertThatThrownBy(() -> router.inTenantSession(tenantA, session -> session.inTransaction(jdbc -> {
				insertProbe(jdbc, tenantA, "rollback-after-revocation");
				centralAdmin.update("UPDATE tenant_management.workspace_membership SET status='REVOKED' WHERE id=?",
						fixture.tenantA().membershipId().value());
				throw new IllegalArgumentException("original callback failure");
			})))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessage("original callback failure");
		}

		assertThat(countProbe(tenantAAdmin, "rollback-after-revocation")).isZero();
		assertThat(centralAdmin.queryForObject("SELECT status FROM tenant_management.workspace_membership WHERE id=?",
				String.class, fixture.tenantA().membershipId().value())).isEqualTo("REVOKED");
	}

	@Test
	void callbackRejectsDirectJdbcAndAsynchronousResultEscapes() {
		CurrentAccessContext tenantA = resolveContext(fixture.tenantA());
		CountingFactory pools = new CountingFactory(credentialsFor(fixture, false));

		try (TenantBusinessDatabaseRouter router = router(pools)) {
			setRequestScope(fixture.tenantA());
			assertThatThrownBy(() -> router.inTransaction(tenantA, jdbc -> jdbc))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("must not return JDBC resources");
			assertThatThrownBy(() -> router.inTransaction(tenantA,
					jdbc -> java.util.concurrent.CompletableFuture.completedFuture(1)))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("or asynchronous results");
		}
	}

	private TenantBusinessDatabaseRouter router(CountingFactory pools) {
		return new TenantBusinessDatabaseRouter(new JdbcTenantBusinessDatabaseAuthority(
				accessContextUseCase(), new JdbcTenantBusinessDatabaseBindingRegistry(centralRuntime)), pools, 2);
	}

	private TenantBusinessDatabaseRouter router(CountingFactory pools, int maxCachedPools) {
		return new TenantBusinessDatabaseRouter(new JdbcTenantBusinessDatabaseAuthority(
				accessContextUseCase(), new JdbcTenantBusinessDatabaseBindingRegistry(centralRuntime)), pools, maxCachedPools);
	}

	private ResolveCurrentAccessContextUseCase accessContextUseCase() {
		VerifiedMembershipResolutionPort resolver = (userId, tenantId, workspaceId) -> centralRuntime.query("""
				SELECT m.id, m.user_id, w.tenant_id, m.workspace_id, m.status,
				       s.authorization_version, t.status AS tenant_status, w.status AS workspace_status,
				       r.id AS role_definition_id, r.code AS role_code, rp.permission_key
				FROM tenant_management.workspace_membership m
				JOIN tenant_management.workspace w ON w.id = m.workspace_id
				JOIN tenant_management.tenant t ON t.id = w.tenant_id
				LEFT JOIN tenant_management.membership_authorization_state s ON s.membership_id = m.id
				LEFT JOIN tenant_management.membership_role_definition a ON a.membership_id = m.id
				LEFT JOIN tenant_management.role_definition r ON r.id = a.role_id AND r.status = 'ACTIVE'
				LEFT JOIN tenant_management.role_permission rp ON rp.role_id = r.id
				WHERE m.user_id = ? AND w.tenant_id = ? AND m.workspace_id = ?
				ORDER BY r.id, rp.permission_key
				""", rows -> {
				if (!rows.next()) return java.util.Optional.empty();
				UUID membershipId = rows.getObject("id", UUID.class);
				UUID resolvedUserId = rows.getObject("user_id", UUID.class);
				UUID resolvedTenantId = rows.getObject("tenant_id", UUID.class);
				UUID resolvedWorkspaceId = rows.getObject("workspace_id", UUID.class);
				String membershipStatus = rows.getString("status");
				String tenantStatus = rows.getString("tenant_status");
				String workspaceStatus = rows.getString("workspace_status");
				long authorizationVersion = rows.getLong("authorization_version");
				Set<MembershipRole> roles = new java.util.LinkedHashSet<>();
				Set<String> roleCodes = new java.util.LinkedHashSet<>();
				Set<String> roleDefinitionIds = new java.util.LinkedHashSet<>();
				Set<String> permissionCodes = new java.util.LinkedHashSet<>();
				do {
					String roleCode = rows.getString("role_code");
					UUID roleDefinitionId = rows.getObject("role_definition_id", UUID.class);
					if (roleCode != null && roleDefinitionId != null) {
						String apiRoleCode = roleCode.toUpperCase(java.util.Locale.ROOT);
						roles.add(MembershipRole.valueOf(apiRoleCode));
						roleCodes.add(apiRoleCode);
						roleDefinitionIds.add(roleDefinitionId.toString());
					}
					String permissionCode = rows.getString("permission_key");
					if (permissionCode != null) permissionCodes.add(permissionCode);
				} while (rows.next());
				var membership = new Membership(new MembershipId(membershipId),
						new UserId(resolvedUserId), new TenantId(resolvedTenantId), new WorkspaceId(resolvedWorkspaceId),
						roles, roleCodes, roleDefinitionIds, MembershipStatus.from(membershipStatus), authorizationVersion);
				var authorization = new EffectiveAuthorization(roleDefinitionIds, roleCodes, permissionCodes, authorizationVersion);
				return java.util.Optional.of(new VerifiedMembership(membership, TenantStatus.from(tenantStatus),
						WorkspaceStatus.from(workspaceStatus), authorization));
			}, userId.value(), tenantId.value(), workspaceId.value());
		return new ResolveCurrentAccessContextService(resolver);
	}

	private CurrentAccessContext resolveContext(TenantRecord tenant) {
		setRequestScope(tenant);
		return accessContextUseCase().resolve(new CurrentAccessRequest(tenant.userId(), tenant.tenantId(),
				tenant.workspaceId(), Surface.PLATFORM));
	}

	private static void insertProbe(TenantBusinessDatabaseRouter router, CurrentAccessContext context, String marker) {
		router.inTransaction(context, jdbc -> insertProbe(jdbc, context, marker));
	}

	private static int insertProbe(JdbcTemplate jdbc, CurrentAccessContext context, String marker) {
		return jdbc.update("INSERT INTO nexa_platform.route_probe (id, tenant_id, workspace_id, marker) VALUES (?, ?, ?, ?)",
				UUID.randomUUID(), context.tenantId().value(), context.workspaceId().value(), marker);
	}

	private static int probeCount(TenantBusinessDatabaseRouter router, CurrentAccessContext context) {
		return router.inTransaction(context, jdbc -> jdbc.queryForObject(
				"SELECT count(*) FROM nexa_platform.route_probe", Integer.class));
	}

	private static int countProbe(JdbcTemplate admin, String marker) {
		return admin.queryForObject("SELECT count(*) FROM nexa_platform.route_probe WHERE marker=?", Integer.class, marker);
	}

	private static int countProbeId(JdbcTemplate admin, UUID id) {
		return admin.queryForObject("SELECT count(*) FROM nexa_platform.route_probe WHERE id=?", Integer.class, id);
	}

	private static void assertForcedParentRls(JdbcTemplate admin, Map<String, String> tablesAndPolicies) {
		tablesAndPolicies.forEach((table, policy) -> {
			String[] relation = table.split("\\.", 2);
			Integer matches = admin.queryForObject("""
					SELECT count(*)
					FROM pg_class c
					JOIN pg_namespace n ON n.oid=c.relnamespace
					JOIN pg_policies p ON p.schemaname=n.nspname AND p.tablename=c.relname
					WHERE n.nspname=? AND c.relname=? AND p.policyname=?
					  AND c.relrowsecurity AND c.relforcerowsecurity
					""", Integer.class, relation[0], relation[1], policy);
			assertThat(matches).as("forced RLS policy %s on %s", policy, table).isEqualTo(1);
		});
	}

	private static ParentScopedChildren seedParentScopedChildren(Connection connection, TenantRecord tenant)
			throws SQLException {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		String ruc = "20" + String.format(java.util.Locale.ROOT, "%09d",
				Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));
		UUID accountId = UUID.randomUUID();
		UUID purchaseRequestId = UUID.randomUUID();
		UUID purchaseRequestLineId = UUID.randomUUID();
		UUID salesOrderId = UUID.randomUUID();
		UUID salesOrderLineId = UUID.randomUUID();
		UUID categoryId = UUID.randomUUID();
		UUID brandId = UUID.randomUUID();
		UUID productFamilyId = UUID.randomUUID();
		UUID skuId = UUID.randomUUID();
		UUID alternateSkuId = UUID.randomUUID();
		UUID warehouseId = UUID.randomUUID();
		UUID zoneId = UUID.randomUUID();
		UUID lotId = UUID.randomUUID();
		UUID alternateLotId = UUID.randomUUID();
		UUID reservationId = UUID.randomUUID();
		UUID reservationLineId = UUID.randomUUID();
		UUID allocationId = UUID.randomUUID();
		UUID shortageId = UUID.randomUUID();
		String productFamilyCode = "rls-family-" + suffix;
		String skuCode = "RLS-SKU-" + suffix;
		String alternateSkuCode = "RLS-ALT-" + suffix;
		String purchaseRequestCatalogItemId = "pr-item-" + suffix;
		String salesOrderCatalogItemId = "so-item-" + suffix;
		String reservationCatalogItemId = "reservation-item-" + suffix;
		String orderNumber = "RLS" + suffix;
		Timestamp createdAt = Timestamp.from(Instant.now());
		Timestamp expiresAt = Timestamp.from(createdAt.toInstant().plusSeconds(86_400));
		LocalDate expirationDate = LocalDate.now().plusDays(365);

		insertFixtureRow(connection, """
				INSERT INTO catalog_management.category
				    (id,tenant_id,workspace_id,slug,name,status,created_at,updated_at)
				VALUES (?,?,?,?,?,'ACTIVE',?,?)
				""", categoryId, tenant.tenantId().value(), tenant.workspaceId().value(),
				"rls-category-" + suffix, "RLS category", createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO catalog_management.brand
				    (id,tenant_id,workspace_id,slug,name,status,created_at,updated_at)
				VALUES (?,?,?,?,?,'ACTIVE',?,?)
				""", brandId, tenant.tenantId().value(), tenant.workspaceId().value(),
				"rls-brand-" + suffix, "RLS brand", createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO catalog_management.product_family
				    (id,tenant_id,workspace_id,family_code,name,description,category_id,brand_id,storage_family,status,
				     created_at,updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)
				""", productFamilyId, tenant.tenantId().value(), tenant.workspaceId().value(), productFamilyCode,
				"RLS family", "Parent-scoped RLS integration fixture", categoryId, brandId, "AMBIENT", createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO catalog_management.sellable_sku
				    (id,tenant_id,workspace_id,family_id,sku_code,presentation,packaging_type,unit_of_measure,created_at,updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?)
				""", skuId, tenant.tenantId().value(), tenant.workspaceId().value(), productFamilyId, skuCode,
				"RLS unit", "UNIT", "UNIT", createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO catalog_management.sellable_sku
				    (id,tenant_id,workspace_id,family_id,sku_code,presentation,packaging_type,unit_of_measure,created_at,updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?)
				""", alternateSkuId, tenant.tenantId().value(), tenant.workspaceId().value(), productFamilyId,
				alternateSkuCode, "RLS alternate unit", "UNIT", "UNIT", createdAt, createdAt);

		insertFixtureRow(connection, """
				INSERT INTO sales.client_account
				    (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,
				     tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,
				     status,created_at,updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
				""", accountId, tenant.tenantId().value(), tenant.workspaceId().value(), "RLS-" + suffix,
				"RLS fixture account", "RLS fixture account", "PE", "RUC", ruc,
				"RETAIL", "RLS fixture", "rls-" + suffix + "@example.test", "+51000000000", "STANDARD",
				"IMMEDIATE", "ACTIVE", createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO sales.purchase_request
				    (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,created_at,updated_at)
				VALUES (?,?,?,?,?,?,'DRAFT','NORMAL',?,?)
				""", purchaseRequestId, tenant.tenantId().value(), tenant.workspaceId().value(), accountId,
				tenant.membershipId().value(), "RLS-PR-" + suffix, createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO sales.purchase_request_line
				    (id,purchase_request_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,
				     unit_price_amount,unit_price_currency,created_at,updated_at,sku_id,product_family_id,
				     product_family_code_snapshot,sku_code_snapshot)
				VALUES (?,?,?,?,?,?,?, ?,?,?, ?,?,?,?,?)
				""", purchaseRequestLineId, purchaseRequestId, purchaseRequestCatalogItemId, "RLS item", "RLS unit",
				new BigDecimal("2"), "UNIT", new BigDecimal("1"), "PEN", createdAt, createdAt, skuId,
				productFamilyId, productFamilyCode, skuCode);
		insertFixtureRow(connection, """
				INSERT INTO sales.sales_order
				    (id,tenant_id,workspace_id,number,client_account_id,created_by_membership_id,priority,currency,
				     total_amount,status,created_at,updated_at,order_source,origin_type)
				VALUES (?,?,?,?,?,?,'NORMAL','PEN',?,'CONFIRMED',?,?,'MANUAL','MANUAL')
				""", salesOrderId, tenant.tenantId().value(), tenant.workspaceId().value(), orderNumber,
				accountId, tenant.membershipId().value(), new BigDecimal("2"), createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO sales.sales_order_line
				    (id,sales_order_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,
				     unit_price_amount,unit_price_currency,line_subtotal,created_at,sku_id,product_family_id,
				     product_family_code_snapshot,sku_code_snapshot)
				VALUES (?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?)
				""", salesOrderLineId, salesOrderId, salesOrderCatalogItemId, "RLS item", "RLS unit",
				new BigDecimal("2"), "UNIT", new BigDecimal("1"), "PEN", new BigDecimal("2"), createdAt,
				skuId, productFamilyId, productFamilyCode, skuCode);

		insertFixtureRow(connection, """
				INSERT INTO warehouse.warehouse
				    (id,tenant_id,workspace_id,code,name,status,created_at,updated_at)
				VALUES (?,?,?,?,?,'ACTIVE',?,?)
				""", warehouseId, tenant.tenantId().value(), tenant.workspaceId().value(), "RLS-" + suffix,
				"RLS warehouse", createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO warehouse.storage_zone
				    (id,tenant_id,workspace_id,warehouse_id,code,name,zone_type,status,created_at,updated_at)
				VALUES (?,?,?,?,?,?,'AMBIENT','ACTIVE',?,?)
				""", zoneId, tenant.tenantId().value(), tenant.workspaceId().value(), warehouseId,
				"RLS-" + suffix, "RLS zone", createdAt, createdAt);
		insertFixtureRow(connection, """
				INSERT INTO warehouse.inventory_lot
				    (id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,
				     received_at,stock_quantity,unit,sku_id)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
				""", lotId, tenant.tenantId().value(), tenant.workspaceId().value(), warehouseId, zoneId,
				reservationCatalogItemId, "BATCH-" + suffix, expirationDate, createdAt, new BigDecimal("10"),
				"UNIT", skuId);
		insertFixtureRow(connection, """
				INSERT INTO warehouse.inventory_lot
				    (id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,
				     received_at,stock_quantity,unit,sku_id)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
				""", alternateLotId, tenant.tenantId().value(), tenant.workspaceId().value(), warehouseId, zoneId,
				reservationCatalogItemId, "ALT-BATCH-" + suffix, expirationDate, createdAt, new BigDecimal("10"),
				"UNIT", skuId);
		insertFixtureRow(connection, """
				INSERT INTO warehouse.inventory_reservation
				    (id,tenant_id,workspace_id,sales_order_id,order_number,client_account_id,status,created_at,updated_at,expires_at)
				VALUES (?,?,?,?,?,?,'SHORTAGE',?,?,?)
				""", reservationId, tenant.tenantId().value(), tenant.workspaceId().value(), salesOrderId,
				orderNumber, accountId, createdAt, createdAt, expiresAt);
		insertFixtureRow(connection, """
				INSERT INTO warehouse.inventory_reservation_line
				    (id,reservation_id,catalog_item_id,requested_quantity,unit,shortage_quantity,sku_id)
				VALUES (?,?,?,?,?,?,?)
				""", reservationLineId, reservationId, reservationCatalogItemId, new BigDecimal("2"), "UNIT",
				new BigDecimal("1"), skuId);
		insertFixtureRow(connection, """
				INSERT INTO warehouse.inventory_reservation_allocation
				    (id,reservation_line_id,lot_id,quantity,unit,expiration_date)
				VALUES (?,?,?,?,?,?)
				""", allocationId, reservationLineId, lotId, new BigDecimal("1"), "UNIT", expirationDate);
		insertFixtureRow(connection, """
				INSERT INTO warehouse.reservation_shortage (id,reservation_line_id,quantity,reason)
				VALUES (?,?,?,?)
				""", shortageId, reservationLineId, new BigDecimal("1"), "RLS fixture shortage");

		return new ParentScopedChildren(purchaseRequestId, purchaseRequestLineId, salesOrderId, salesOrderLineId,
				productFamilyId, productFamilyCode, skuId, skuCode, alternateSkuId, reservationId,
				reservationLineId, allocationId, shortageId, alternateLotId, expirationDate, createdAt, suffix);
	}

	private static void insertFixtureRow(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) {
				statement.setObject(index + 1, values[index]);
			}
			statement.executeUpdate();
		}
	}

	private static String currentDatabaseRole(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement(); var result = statement.executeQuery("SELECT current_user")) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}

	private static void assertRuntimeCanSelectAndInsert(Connection connection, String table) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT has_table_privilege('nexa_runtime', ?, 'SELECT'),
			       has_table_privilege('nexa_runtime', ?, 'INSERT')
				""")) {
			statement.setString(1, table);
			statement.setString(2, table);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getBoolean(1)).as("nexa_runtime SELECT on %s", table).isTrue();
				assertThat(result.getBoolean(2)).as("nexa_runtime INSERT on %s", table).isTrue();
			}
		}
	}

	private static void setDatabaseScope(Connection connection, TenantRecord tenant) throws SQLException {
		setDatabaseScope(connection, tenant.tenantId().value(), tenant.workspaceId().value());
	}

	private static void setDatabaseScope(Connection connection, UUID tenantId, UUID workspaceId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT set_config('app.current_tenant_id', ?, true),
			       set_config('app.current_workspace_id', ?, true)
				""")) {
			statement.setString(1, tenantId.toString());
			statement.setString(2, workspaceId.toString());
			statement.execute();
		}
	}

	private static void setDatabaseSessionScope(Connection connection, UUID tenantId, UUID workspaceId)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT set_config('app.current_tenant_id', ?, false),
				       set_config('app.current_workspace_id', ?, false)
				""")) {
			statement.setString(1, tenantId.toString());
			statement.setString(2, workspaceId.toString());
			statement.execute();
		}
	}

	private static long changeEventCount(Statement statement, UUID eventId) throws SQLException {
		try (var result = statement.executeQuery("SELECT count(*) FROM integration.change_event WHERE event_id='"
				+ eventId + "'")) {
			assertThat(result.next()).isTrue();
			return result.getLong(1);
		}
	}

	private static void insertChangeEvent(Connection connection, UUID eventId, TenantRecord tenant) throws SQLException {
		Instant now = Instant.now();
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO integration.change_event
					(event_id,tenant_id,workspace_id,aggregate_type,aggregate_id,event_type,audiences,occurred_at,expires_at)
				VALUES (?,?,?,'scope-test',?,'scope-test.event',ARRAY['OWNER'],?,?)
				""")) {
			statement.setObject(1, eventId);
			statement.setObject(2, tenant.tenantId().value());
			statement.setObject(3, tenant.workspaceId().value());
			statement.setObject(4, UUID.randomUUID());
			statement.setTimestamp(5, Timestamp.from(now));
			statement.setTimestamp(6, Timestamp.from(now.minusSeconds(1)));
			statement.executeUpdate();
		}
	}

	private static void assertChildCount(Connection connection, String table, UUID childId, long expectedCount)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE id=?")) {
			statement.setObject(1, childId);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).as("visible rows in %s for child %s", table, childId).isEqualTo(expectedCount);
			}
		}
	}

	private static void assertNotificationPreferenceCount(Connection connection, UUID workspaceId, long expectedCount)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT count(*) FROM tenant_management.notification_preference
				WHERE workspace_id=? AND event_category='ORDER_STATUS' AND channel='IN_APP'
				""")) {
			statement.setObject(1, workspaceId);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getLong(1)).isEqualTo(expectedCount);
			}
		}
	}

	private static void assertRlsRejectedInsert(Connection connection, String table, String sql, Object... values)
			throws SQLException {
		Savepoint savepoint = connection.setSavepoint();
		SQLException failure = null;
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) {
				statement.setObject(index + 1, values[index]);
			}
			statement.executeUpdate();
		} catch (SQLException exception) {
			failure = exception;
		} finally {
			connection.rollback(savepoint);
			connection.releaseSavepoint(savepoint);
		}
		assertThat(failure == null).as("wrong-workspace INSERT into %s is rejected by RLS", table).isFalse();
		assertThat(failure.getSQLState()).as("RLS SQLSTATE for INSERT into %s", table).isEqualTo("42501");
		assertThat(failure.getMessage()).as("INSERT into %s fails the row-level security policy", table)
				.contains("row-level security policy");
	}

	private static Map<String, TenantBusinessDatabaseCredentials> credentialsFor(TestFixture fixture,
			boolean routeTenantAToTenantB) {
		TenantRecord credentialsForA = routeTenantAToTenantB ? fixture.tenantB() : fixture.tenantA();
		return Map.of(
				fixture.tenantA().credentialSecretReference(), new TenantBusinessDatabaseCredentials(credentialsForA.container().getJdbcUrl(),
						RUNTIME_USERNAME, credentialsForA.runtimePassword()),
				fixture.tenantB().credentialSecretReference(), new TenantBusinessDatabaseCredentials(fixture.tenantB().container().getJdbcUrl(),
						RUNTIME_USERNAME, fixture.tenantB().runtimePassword()));
	}

	private static TestFixture seedFixture(JdbcTemplate central, JdbcTemplate tenantAAdmin, JdbcTemplate tenantBAdmin) {
		UserId userId = UserId.random();
		seedUser(central, userId);
		TenantRecord tenantA = seedTenant(central, "Otto Kunz", "otto-kunz", userId, "tenant-a",
				TENANT_A, TENANT_A_RUNTIME_PASSWORD);
		TenantRecord tenantB = seedTenant(central, "ICISA", "icisa", userId, "tenant-b",
				TENANT_B, TENANT_B_RUNTIME_PASSWORD);
		insertDatabaseIdentity(tenantAAdmin, tenantA);
		insertDatabaseIdentity(tenantBAdmin, tenantB);
		return new TestFixture(tenantA, tenantB);
	}

	private static TenantRecord seedTenant(JdbcTemplate central, String tenantName, String tenantSlug, UserId userId,
			String secretSuffix, PostgreSQLContainer container, String runtimePassword) {
		TenantId tenantId = TenantId.random();
		WorkspaceId workspaceId = WorkspaceId.random();
		MembershipId membershipId = MembershipId.random();
		UUID databaseIdentity = UUID.randomUUID();
		String secretReference = "secret-ref/" + secretSuffix + "/" + tenantId;
		Timestamp now = Timestamp.from(Instant.now());
		central.update("INSERT INTO tenant_management.tenant (id,name,slug,status,created_at,updated_at) VALUES (?,?,?,?,?,?)",
				tenantId.value(), tenantName, tenantSlug + "-" + tenantId, "ACTIVE", now, now);
		central.update("INSERT INTO tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
				workspaceId.value(), tenantId.value(), tenantName + " Workspace", "primary", "ACTIVE", now, now);
		central.update("INSERT INTO tenant_management.workspace_membership (id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
				membershipId.value(), tenantId.value(), workspaceId.value(), userId.value(), "INTERNAL", "ACTIVE", now, now);
		central.update("""
				INSERT INTO tenant_management.membership_role_definition
				    (membership_id,tenant_id,workspace_id,role_id,assigned_at)
				SELECT ?,?,?,id,? FROM tenant_management.role_definition
				WHERE tenant_id IS NULL AND code='company_owner'
				""", membershipId.value(), tenantId.value(), workspaceId.value(), now);
		central.update("""
				INSERT INTO tenant_management.membership_authorization_state
				    (membership_id,tenant_id,workspace_id,authorization_version,updated_at)
				VALUES (?,?,?,0,?) ON CONFLICT (membership_id) DO NOTHING
				""", membershipId.value(), tenantId.value(), workspaceId.value(), now);
		central.update("INSERT INTO tenant_management.tenant_business_database_binding (tenant_id,database_identity,credential_secret_reference,lifecycle_state) VALUES (?,?,?,?)",
				tenantId.value(), databaseIdentity, secretReference, "READY");
		return new TenantRecord(tenantId, workspaceId, membershipId, userId, databaseIdentity,
				secretReference, container, runtimePassword);
	}

	private static void seedUser(JdbcTemplate central, UserId userId) {
		Timestamp now = Timestamp.from(Instant.now());
		central.update("INSERT INTO iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
				userId.value(), userId + "@example.test", userId + "@example.test", userId.toString(),
				userId.toString(), "Test User", "en", "ACTIVE", now, now);
	}

	private static void insertDatabaseIdentity(JdbcTemplate admin, TenantRecord tenant) {
		admin.update("INSERT INTO nexa_platform.tenant_business_database_identity (tenant_id,database_identity) VALUES (?,?)",
				tenant.tenantId().value(), tenant.databaseIdentity());
		admin.update("INSERT INTO nexa_platform.tenant_workspace_scope_anchor (tenant_id,workspace_id) VALUES (?,?)",
				tenant.tenantId().value(), tenant.workspaceId().value());
	}

	private static List<CatalogFixtureProjection> provisionCatalogFixture(JdbcTemplate jdbc, TenantRecord tenant,
			List<CatalogPersistenceSeedItemRecord> items,
			Map<String, CatalogFamilySkuMappingLoader.MappingItem> mappings) {
		jdbc.execute("""
				CREATE TABLE nexa_platform.tenant_catalog_fixture_probe (
				    tenant_id UUID NOT NULL,
				    workspace_id UUID NOT NULL,
				    catalog_item_id VARCHAR(64) NOT NULL,
				    product_code VARCHAR(64) NOT NULL,
				    product_id UUID NOT NULL,
				    family_code VARCHAR(80) NOT NULL,
				    sku_code VARCHAR(80) NOT NULL,
				    sellable_sku_id UUID NOT NULL,
				    buyer_visible BOOLEAN NOT NULL,
				    provisional_reference BOOLEAN NOT NULL,
				    source_price_code VARCHAR(80) NOT NULL,
				    PRIMARY KEY (tenant_id, workspace_id, catalog_item_id),
				    CONSTRAINT fk_tenant_catalog_fixture_scope FOREIGN KEY (tenant_id, workspace_id)
				        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id)
				)
				""");
		for (CatalogPersistenceSeedItemRecord item : items) {
			CatalogFamilySkuMappingLoader.MappingItem mapping = mappings.get(item.catalogItemId());
			UUID productId = catalogProductId(tenant, item.productId());
			jdbc.update("""
					INSERT INTO nexa_platform.tenant_catalog_fixture_probe
					    (tenant_id,workspace_id,catalog_item_id,product_code,product_id,family_code,sku_code,sellable_sku_id,
				     buyer_visible,provisional_reference,source_price_code)
				VALUES (?,?,?,?,?,?,?,?,?,?,?)
				""", tenant.tenantId().value(), tenant.workspaceId().value(), item.catalogItemId(), item.productId(), productId,
					mapping.familyCode(), mapping.skuCode(), productId, item.buyerVisible(), item.provisionalReference(), item.sourcePriceCode());
		}
		return jdbc.query("""
				SELECT catalog_item_id,product_code,product_id,family_code,sku_code,sellable_sku_id,
				       buyer_visible,provisional_reference,source_price_code
				FROM nexa_platform.tenant_catalog_fixture_probe
				WHERE tenant_id=? AND workspace_id=?
				ORDER BY catalog_item_id
				""", (result, row) -> new CatalogFixtureProjection(result.getString("catalog_item_id"),
					result.getString("product_code"), result.getObject("product_id", UUID.class),
					result.getString("family_code"), result.getString("sku_code"),
					result.getObject("sellable_sku_id", UUID.class), result.getBoolean("buyer_visible"),
					result.getBoolean("provisional_reference"), result.getString("source_price_code")),
				tenant.tenantId().value(), tenant.workspaceId().value());
	}

	private static List<CatalogFixtureProjection> expectedCatalogFixture(TenantRecord tenant,
			List<CatalogPersistenceSeedItemRecord> items,
			Map<String, CatalogFamilySkuMappingLoader.MappingItem> mappings) {
		return items.stream().map(item -> {
			CatalogFamilySkuMappingLoader.MappingItem mapping = mappings.get(item.catalogItemId());
			UUID productId = catalogProductId(tenant, item.productId());
			return new CatalogFixtureProjection(item.catalogItemId(), item.productId(), productId, mapping.familyCode(),
					mapping.skuCode(), productId, item.buyerVisible(), item.provisionalReference(), item.sourcePriceCode());
		}).toList();
	}

	private static UUID catalogProductId(TenantRecord tenant, String productCode) {
		return UUID.nameUUIDFromBytes((tenant.tenantId() + ":" + tenant.workspaceId() + ":product:" + productCode)
				.getBytes(StandardCharsets.UTF_8));
	}

	private static void dropCatalogFixtureProbe(JdbcTemplate jdbc) {
		jdbc.execute("DROP TABLE IF EXISTS nexa_platform.tenant_catalog_fixture_probe");
	}

	private static void prepareProbeTable(PostgreSQLContainer container) {
		try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE nexa_platform.route_probe (
					    id UUID PRIMARY KEY,
					    tenant_id UUID NOT NULL,
					    workspace_id UUID NOT NULL,
					    marker TEXT NOT NULL,
					    CONSTRAINT fk_route_probe_workspace_scope FOREIGN KEY (tenant_id, workspace_id)
					        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id)
					)
					""");
			statement.execute("ALTER TABLE nexa_platform.route_probe ENABLE ROW LEVEL SECURITY");
			statement.execute("ALTER TABLE nexa_platform.route_probe FORCE ROW LEVEL SECURITY");
			statement.execute("""
					CREATE POLICY route_probe_tenant_workspace_scope ON nexa_platform.route_probe
					USING (
					    tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid
					    AND workspace_id = nullif(current_setting('app.current_workspace_id', true), '')::uuid
					)
					WITH CHECK (
					    tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid
					    AND workspace_id = nullif(current_setting('app.current_workspace_id', true), '')::uuid
					)
					""");
			statement.execute("GRANT INSERT, SELECT ON nexa_platform.route_probe TO nexa_runtime");
		} catch (Exception exception) {
			throw new IllegalStateException("Could not prepare Tenant database routing probe table", exception);
		}
	}

	private static void configureRuntimeRole(PostgreSQLContainer container, String runtimePassword) {
		try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("CREATE ROLE nexa_runtime LOGIN NOINHERIT PASSWORD '" + runtimePassword + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
		} catch (Exception exception) {
			throw new IllegalStateException("Could not create the restricted runtime role for routing integration tests", exception);
		}
	}

	private static void configurePolicySnapshotWriterRole(PostgreSQLContainer container) {
		try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("CREATE ROLE nexa_policy_snapshot_writer LOGIN PASSWORD '"
					+ TENANT_POLICY_SNAPSHOT_WRITER_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("GRANT CONNECT ON DATABASE \"" + container.getDatabaseName() + "\" TO nexa_policy_snapshot_writer");
		} catch (Exception exception) {
			throw new IllegalStateException("Could not create the dedicated policy-snapshot writer test role", exception);
		}
	}

	private static void configureWorkerRoles(PostgreSQLContainer container) {
		try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("CREATE ROLE nexa_business_documents_worker LOGIN PASSWORD '"
					+ DOCUMENTS_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_payments_worker LOGIN PASSWORD '" + PAYMENTS_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_business_traceability_worker LOGIN PASSWORD '"
					+ TRACEABILITY_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_warehouse_reservation_expiry_worker NOLOGIN NOINHERIT "
					+ "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("GRANT CONNECT ON DATABASE \"" + container.getDatabaseName()
					+ "\" TO nexa_business_documents_worker, nexa_payments_worker, nexa_business_traceability_worker");
		} catch (Exception exception) {
			throw new IllegalStateException("Could not create Tenant worker roles required by current migrations", exception);
		}
	}

	private static PostgreSQLContainer container(String databaseName) {
		return new PostgreSQLContainer("postgres:18.4-alpine")
				.withDatabaseName(databaseName).withUsername("nexa_admin").withPassword("migration-only-test-password");
	}

	private static JdbcTemplate adminJdbc(PostgreSQLContainer container) {
		return new JdbcTemplate(adminDataSource(container));
	}

	private static DataSource adminDataSource(PostgreSQLContainer container) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(container.getJdbcUrl());
		dataSource.setUsername(container.getUsername());
		dataSource.setPassword(container.getPassword());
		return dataSource;
	}

	private static DataSource runtimeDataSource(PostgreSQLContainer container, String password) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(container.getJdbcUrl());
		dataSource.setUsername(RUNTIME_USERNAME);
		dataSource.setPassword(password);
		return dataSource;
	}

	private static void setRequestScope(TenantRecord tenant) {
		RlsRequestScope.set(tenant.tenantId().value(), tenant.workspaceId().value());
	}

	private record TestFixture(TenantRecord tenantA, TenantRecord tenantB) { }

	private record ParentScopedChildren(UUID purchaseRequestId, UUID purchaseRequestLineId, UUID salesOrderId,
			UUID salesOrderLineId, UUID productFamilyId, String productFamilyCode, UUID skuId, String skuCode,
			UUID alternateSkuId, UUID reservationId, UUID reservationLineId, UUID allocationId, UUID shortageId,
			UUID alternateLotId, LocalDate expirationDate, Timestamp createdAt, String suffix) {
		private String deniedPurchaseRequestCatalogItemId() { return "denied-pr-item-" + suffix; }
		private String deniedSalesOrderCatalogItemId() { return "denied-so-item-" + suffix; }
		private String deniedReservationCatalogItemId() { return "denied-reservation-item-" + suffix; }
	}

	private record TenantRecord(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId,
			UserId userId, UUID databaseIdentity, String credentialSecretReference,
			PostgreSQLContainer container, String runtimePassword) { }

	private record CatalogFixtureProjection(String catalogItemId, String productCode, UUID productId,
			String familyCode, String skuCode, UUID sellableSkuId, boolean buyerVisible,
			boolean provisionalReference, String sourcePriceCode) { }

	private static final class CountingFactory implements com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDataSourceFactory {
		private final HikariTenantBusinessDatabaseDataSourceFactory delegate;
		private final java.util.concurrent.CopyOnWriteArrayList<String> createdSecrets = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final java.util.concurrent.CopyOnWriteArrayList<com.zaxxer.hikari.HikariDataSource> createdDataSources = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final AtomicInteger createdCount = new AtomicInteger();

		private CountingFactory(Map<String, TenantBusinessDatabaseCredentials> credentialsByReference) {
			this.delegate = new HikariTenantBusinessDatabaseDataSourceFactory(reference -> {
				TenantBusinessDatabaseCredentials credentials = credentialsByReference.get(reference);
				if (credentials == null) throw new IllegalArgumentException("No test business credentials for secret reference");
				return credentials;
			}, 2);
		}

		@Override
		public DataSource create(TenantBusinessDatabaseBinding binding) {
			createdCount.incrementAndGet();
			createdSecrets.add(binding.credentialSecretReference());
			com.zaxxer.hikari.HikariDataSource dataSource = delegate.create(binding);
			createdDataSources.add(dataSource);
			return dataSource;
		}

		private int createdCount() { return createdCount.get(); }
		private java.util.List<String> createdSecrets() { return java.util.List.copyOf(createdSecrets); }
		private java.util.List<com.zaxxer.hikari.HikariDataSource> createdDataSources() { return java.util.List.copyOf(createdDataSources); }
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out awaiting test transaction release");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while awaiting test transaction release", exception);
		}
	}

	private static Throwable rootCause(Throwable exception) {
		Throwable root = exception;
		while (root.getCause() != null) root = root.getCause();
		return root;
	}
}
