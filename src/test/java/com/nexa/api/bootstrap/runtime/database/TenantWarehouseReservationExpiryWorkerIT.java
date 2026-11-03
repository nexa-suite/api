package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.inventoryavailability.infrastructure.persistence.WarehouseReservationPersistenceAdapter;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryReservationExpiryWorkerRouter;
import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that an expiry worker mutates only the physical Tenant database it routed to. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantWarehouseReservationExpiryWorkerIT extends PostgresIntegrationSupport {
	private static final BigDecimal RESERVED_QUANTITY = new BigDecimal("2.0000");

	@Autowired
	private TenantInventoryReservationExpiryWorkerRouter expiryWorker;

	@Autowired
	private TenantChangeEventPersistenceFactory changeEvents;

	@Autowired
	private TenantBusinessDatabaseBindingRegistry databaseBindings;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void expiresReservationsOnlyInsideTheSelectedTenantDatabase() {
		UUID tenantA = UUID.fromString(tenantId());
		UUID workspaceA = UUID.fromString(workspaceId());
		TenantWorkspace tenantB = createReadyTenantWorkspace();
		TenantWorkspace legacyTenant = createTenantWorkspaceWithoutDatabase();
		Boolean legacyRouteUnbound = lockAndCheckNoBinding(legacyTenant.tenantId(), legacyTenant.workspaceId());
		assertThat(legacyRouteUnbound).isTrue();
		Boolean routedTenantIsNotLegacy = lockAndCheckNoBinding(tenantA, workspaceA);
		assertThat(routedTenantIsNotLegacy).isFalse();
		seedTenantCatalogFixture(tenantB.tenantId(), tenantB.workspaceId());

		// The same aggregate id in both physical databases makes a central or
		// cross-Tenant write visible immediately in the assertions below.
		UUID reservationId = UUID.randomUUID();
		ReservationFixture fixtureA = seedExpiredReservation(tenantA, workspaceA, reservationId);
		ReservationFixture fixtureB = seedExpiredReservation(tenantB.tenantId(), tenantB.workspaceId(), reservationId);

		assertReservation(tenantJdbcFor(tenantA), reservationId, "RESERVED", RESERVED_QUANTITY);
		assertReservation(tenantJdbcFor(tenantB.tenantId()), reservationId, "RESERVED", RESERVED_QUANTITY);

		expire(tenantA, workspaceA);

		assertReservation(tenantJdbcFor(tenantA), reservationId, "EXPIRED", BigDecimal.ZERO);
		assertReservation(tenantJdbcFor(tenantB.tenantId()), reservationId, "RESERVED", RESERVED_QUANTITY);
		assertExpiryEvidence(tenantJdbcFor(tenantA), reservationId, fixtureA.lotId());
		assertNoExpiryEvidence(tenantJdbcFor(tenantB.tenantId()), reservationId);
		assertThat(jdbc.queryForObject("select count(*) from warehouse.inventory_reservation where id=?",
				Integer.class, reservationId)).isZero();

		expire(tenantB.tenantId(), tenantB.workspaceId());

		assertReservation(tenantJdbcFor(tenantB.tenantId()), reservationId, "EXPIRED", BigDecimal.ZERO);
		assertExpiryEvidence(tenantJdbcFor(tenantB.tenantId()), reservationId, fixtureB.lotId());
	}

	@Test
	void bindingGuardRejectsMissingMismatchedAndCrossScopeContexts() {
		UUID tenantId = UUID.randomUUID();
		UUID otherTenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();

		assertThatThrownBy(() -> databaseBindings.hasAnyBinding(new TenantId(tenantId)))
				.isInstanceOf(IllegalStateException.class);

		RlsRequestScope.set(otherTenantId, workspaceId);
		try {
			assertThatThrownBy(() -> databaseBindings.hasAnyBinding(new TenantId(tenantId)))
					.isInstanceOf(IllegalStateException.class);
			RlsRequestScope.enableCrossScopeWorkspaceScan();
			assertThatThrownBy(() -> databaseBindings.hasAnyBinding(new TenantId(otherTenantId)))
					.isInstanceOf(IllegalStateException.class);
		} finally {
			RlsRequestScope.clear();
		}
	}

	@Test
	void firstBindingInsertWaitsForLegacyTenantExpiryLock() throws Exception {
		TenantWorkspace legacyTenant = createTenantWorkspaceWithoutDatabase();
		DriverManagerDataSource runtimeDataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), RUNTIME_USERNAME, RUNTIME_PASSWORD);
		JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDataSource);
		TenantBusinessDatabaseBindingRegistry runtimeBindings =
				new com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry(runtimeJdbc);
		PlatformTransactionManager runtimeTransactionManager = new DataSourceTransactionManager(runtimeDataSource);
		CountDownLatch expiryLockHeld = new CountDownLatch(1);
		CountDownLatch releaseExpiryLock = new CountDownLatch(1);
		CountDownLatch bindingInsertStarted = new CountDownLatch(1);
		AtomicInteger expiryBackendPid = new AtomicInteger();
		AtomicInteger bindingBackendPid = new AtomicInteger();
		AtomicReference<Throwable> expiryFailure = new AtomicReference<>();
		AtomicReference<Throwable> bindingFailure = new AtomicReference<>();
		UUID databaseIdentity = UUID.randomUUID();
		String credentialReference = "expiry-lock-test:" + UUID.randomUUID();

		Thread expiryTransaction = new Thread(() -> {
			RlsRequestScope.set(legacyTenant.tenantId(), legacyTenant.workspaceId());
			try {
				new TransactionTemplate(runtimeTransactionManager).executeWithoutResult(status -> {
					setDatabaseScope(runtimeJdbc, legacyTenant);
					expiryBackendPid.set(runtimeJdbc.queryForObject("select pg_backend_pid()", Integer.class));
					if (!runtimeBindings.lockAndCheckNoBinding(new TenantId(legacyTenant.tenantId()))) {
						throw new AssertionError("An unbound Tenant must be eligible for the central legacy route");
					}
					expiryLockHeld.countDown();
					await(releaseExpiryLock, 20);
				});
			} catch (Throwable failure) {
				expiryFailure.set(failure);
			} finally {
				RlsRequestScope.clear();
			}
		}, "legacy-expiry-lock-test");
		Thread bindingTransaction = new Thread(() -> {
			RlsRequestScope.set(legacyTenant.tenantId(), legacyTenant.workspaceId());
			try {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					bindingBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
					bindingInsertStarted.countDown();
					jdbc.update("insert into tenant_management.tenant_business_database_binding "
							+ "(tenant_id,database_identity,credential_secret_reference,lifecycle_state) "
							+ "values (?,?,?,'PROVISIONING')", legacyTenant.tenantId(), databaseIdentity,
							credentialReference);
				});
			} catch (Throwable failure) {
				bindingFailure.set(failure);
			} finally {
				RlsRequestScope.clear();
			}
		}, "tenant-binding-insert-test");

		expiryTransaction.start();
		try {
			assertThat(expiryLockHeld.await(10, TimeUnit.SECONDS)).isTrue();
			bindingTransaction.start();
			assertThat(bindingInsertStarted.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(bindingBackendPid.get()).isPositive();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			boolean blockedByExpiry = false;
			while (System.nanoTime() < deadline) {
				Boolean blockerMatchesExpiry = jdbc.queryForObject(
						"select ? = any(pg_blocking_pids(?))", Boolean.class,
						expiryBackendPid.get(), bindingBackendPid.get());
				if (Boolean.TRUE.equals(blockerMatchesExpiry)) {
					blockedByExpiry = true;
					break;
				}
				Thread.sleep(10);
			}
			assertThat(blockedByExpiry).as("binding FK insert must wait for the Tenant row lock").isTrue();
		} finally {
			releaseExpiryLock.countDown();
			expiryTransaction.join(TimeUnit.SECONDS.toMillis(10));
			if (bindingTransaction.isAlive()) bindingTransaction.join(TimeUnit.SECONDS.toMillis(10));
		}

		assertThat(expiryTransaction.isAlive()).isFalse();
		assertThat(bindingTransaction.isAlive()).isFalse();
		assertThat(expiryFailure.get()).isNull();
		assertThat(bindingFailure.get()).isNull();
		RlsRequestScope.set(legacyTenant.tenantId(), legacyTenant.workspaceId());
		Boolean isBound;
		try {
			isBound = new TransactionTemplate(runtimeTransactionManager).execute(status -> {
				setDatabaseScope(runtimeJdbc, legacyTenant);
				return runtimeBindings.lockAndCheckNoBinding(new TenantId(legacyTenant.tenantId()));
			});
		} finally {
			RlsRequestScope.clear();
		}
		assertThat(isBound).isFalse();
	}

	private Boolean lockAndCheckNoBinding(UUID tenantId, UUID workspaceId) {
		RlsRequestScope.set(tenantId, workspaceId);
		try {
			return new TransactionTemplate(transactionManager).execute(status ->
					databaseBindings.lockAndCheckNoBinding(new TenantId(tenantId)));
		} finally {
			RlsRequestScope.clear();
		}
	}

	private TenantWorkspace createReadyTenantWorkspace() {
		TenantWorkspace workspace = createTenantWorkspaceWithoutDatabase();
		provisionTenantBusinessDatabase(workspace.tenantId(), workspace.workspaceId());
		return workspace;
	}

	private TenantWorkspace createTenantWorkspaceWithoutDatabase() {
		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		String suffix = tenantId.toString().replace("-", "").substring(0, 12);
		jdbc.update("insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) "
				+ "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp)",
			tenantId, "Reservation expiry integration Tenant", "expiry-" + suffix);
		jdbc.update("insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) "
				+ "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp)",
			workspaceId, tenantId, "Reservation expiry integration Workspace", "expiry-ws-" + suffix);
		return new TenantWorkspace(tenantId, workspaceId);
	}

	private ReservationFixture seedExpiredReservation(UUID tenantId, UUID workspaceId, UUID reservationId) {
		JdbcTemplate tenantJdbc = tenantJdbcFor(tenantId);
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		SkuFixture sku = tenantJdbc.query("select id, coalesce(nullif(legacy_catalog_item_id,''),sku_code) as item_id, "
				+ "unit_of_measure from catalog_management.sellable_sku where tenant_id=? and workspace_id=? "
				+ "and status='ACTIVE' order by sku_code limit 1", (ResultSet result, int row) -> new SkuFixture(
				result.getObject("id", UUID.class), result.getString("item_id"), result.getString("unit_of_measure")),
			tenantId, workspaceId).stream().findFirst().orElseThrow(
				() -> new IllegalStateException("Tenant catalog fixture must provide an active SKU"));
		UUID accountId = UUID.randomUUID();
		UUID salesOrderId = UUID.randomUUID();
		UUID warehouseId = UUID.randomUUID();
		UUID zoneId = UUID.randomUUID();
		UUID lotId = UUID.randomUUID();
		UUID reservationLineId = UUID.randomUUID();
		UUID allocationId = UUID.randomUUID();
		String orderNumber = "E" + suffix;

		tenantJdbc.update("insert into sales.client_account "
				+ "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,"
				+ "tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,"
				+ "created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',current_timestamp,current_timestamp)",
			accountId, tenantId, workspaceId, "EXP-" + suffix, "Expiry test account", "Expiry test account", "PE",
			"RUC", "20" + String.format(java.util.Locale.ROOT, "%09d",
					Math.floorMod(accountId.getMostSignificantBits(), 1_000_000_000L)), "RETAIL", "Integration fixture",
			"expiry-" + suffix + "@example.test", "+51000000000",
			"STANDARD", "IMMEDIATE");
		tenantJdbc.update("insert into sales.sales_order "
				+ "(id,tenant_id,workspace_id,number,client_account_id,created_by_membership_id,priority,currency,total_amount,"
				+ "status,created_at,updated_at,order_source,origin_type) "
				+ "values (?,?,?,?,?,?,'NORMAL','PEN',?,'CONFIRMED',current_timestamp,current_timestamp,'MANUAL','MANUAL')",
			salesOrderId, tenantId, workspaceId, orderNumber, accountId, UUID.randomUUID(), new BigDecimal("2.0000"));
		tenantJdbc.update("insert into warehouse.warehouse "
				+ "(id,tenant_id,workspace_id,code,name,status,created_at,updated_at) "
				+ "values (?,?,?,?,'Expiry fixture warehouse','ACTIVE',current_timestamp,current_timestamp)",
			warehouseId, tenantId, workspaceId, "EXP-" + suffix);
		tenantJdbc.update("insert into warehouse.storage_zone "
				+ "(id,tenant_id,workspace_id,warehouse_id,code,name,zone_type,status,created_at,updated_at) "
				+ "values (?,?,?,?,?,'Expiry fixture zone','AMBIENT','ACTIVE',current_timestamp,current_timestamp)",
			zoneId, tenantId, workspaceId, warehouseId, "EXP-" + suffix);
		tenantJdbc.update("insert into warehouse.inventory_lot "
				+ "(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,received_at,"
				+ "stock_quantity,reserved_quantity,unit,status,sku_id) "
				+ "values (?,?,?,?,?,?,?,current_date + 30,current_timestamp,10,? ,?,'AVAILABLE',?)",
			lotId, tenantId, workspaceId, warehouseId, zoneId, sku.catalogItemId(), "EXP-" + suffix,
			RESERVED_QUANTITY, sku.unit(), sku.id());
		tenantJdbc.update("insert into warehouse.inventory_reservation "
				+ "(id,tenant_id,workspace_id,sales_order_id,order_number,client_account_id,status,created_at,updated_at,"
				+ "reserved_at,expires_at) values (?,?,?,?,?,?,'RESERVED',current_timestamp,current_timestamp,"
				+ "current_timestamp,current_timestamp - interval '1 minute')",
			reservationId, tenantId, workspaceId, salesOrderId, orderNumber, accountId);
		tenantJdbc.update("insert into warehouse.inventory_reservation_line "
				+ "(id,reservation_id,catalog_item_id,requested_quantity,unit,shortage_quantity,sku_id) "
				+ "values (?,?,?,? ,?,0,?)",
			reservationLineId, reservationId, sku.catalogItemId(), RESERVED_QUANTITY, sku.unit(), sku.id());
		tenantJdbc.update("insert into warehouse.inventory_reservation_allocation "
				+ "(id,reservation_line_id,lot_id,quantity,unit,expiration_date) "
				+ "values (?,?,?,?,?,current_date + 30)",
			allocationId, reservationLineId, lotId, RESERVED_QUANTITY, sku.unit());
		return new ReservationFixture(lotId);
	}

	private void expire(UUID tenantId, UUID workspaceId) {
		expiryWorker.inTransaction(tenantId, workspaceId, (tenantJdbc, transactionManager) -> {
			var persistence = new WarehouseReservationPersistenceAdapter(tenantJdbc,
					changeEvents.bindTo(tenantJdbc), transactionManager);
			persistence.expireReservationsForScope(tenantId, workspaceId);
			return null;
		});
	}

	private static void assertReservation(JdbcTemplate tenantJdbc, UUID reservationId, String status,
			BigDecimal reservedQuantity) {
		assertThat(tenantJdbc.queryForObject("select status from warehouse.inventory_reservation where id=?",
				String.class, reservationId)).isEqualTo(status);
		assertThat(tenantJdbc.queryForObject("select reserved_quantity from warehouse.inventory_lot "
				+ "where id=(select lot_id from warehouse.inventory_reservation_allocation a "
				+ "join warehouse.inventory_reservation_line l on l.id=a.reservation_line_id "
				+ "where l.reservation_id=?)", BigDecimal.class, reservationId)).isEqualByComparingTo(reservedQuantity);
	}

	private static void assertExpiryEvidence(JdbcTemplate tenantJdbc, UUID reservationId, UUID lotId) {
		assertThat(tenantJdbc.queryForObject("select count(*) from warehouse.stock_movement "
				+ "where lot_id=? and movement_type='RESERVATION_EXPIRATION'", Integer.class, lotId)).isEqualTo(1);
		assertThat(tenantJdbc.queryForObject("select count(*) from warehouse.inventory_event "
				+ "where aggregate_id=? and event_type='warehouse.reservation.expired'", Integer.class, reservationId)).isEqualTo(1);
		assertThat(tenantJdbc.queryForObject("select count(*) from integration.change_event "
				+ "where aggregate_id=? and event_type='warehouse.reservation.expired'", Integer.class, reservationId)).isEqualTo(1);
	}

	private static void assertNoExpiryEvidence(JdbcTemplate tenantJdbc, UUID reservationId) {
		assertThat(tenantJdbc.queryForObject("select count(*) from warehouse.stock_movement "
				+ "where correlation_id=? and movement_type='RESERVATION_EXPIRATION'", Integer.class,
				"reservation-expiry-" + reservationId)).isZero();
		assertThat(tenantJdbc.queryForObject("select count(*) from warehouse.inventory_event "
				+ "where aggregate_id=? and event_type='warehouse.reservation.expired'", Integer.class, reservationId)).isZero();
		assertThat(tenantJdbc.queryForObject("select count(*) from integration.change_event "
				+ "where aggregate_id=? and event_type='warehouse.reservation.expired'", Integer.class, reservationId)).isZero();
	}

	private static void await(CountDownLatch latch, int timeoutSeconds) {
		try {
			if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for integration-test release");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Integration-test transaction was interrupted", exception);
		}
	}

	private static void setDatabaseScope(JdbcTemplate jdbc, TenantWorkspace workspace) {
		jdbc.queryForObject("select set_config('app.current_tenant_id', ?, true) || "
				+ "set_config('app.current_workspace_id', ?, true)", String.class,
			workspace.tenantId().toString(), workspace.workspaceId().toString());
	}

	private record TenantWorkspace(UUID tenantId, UUID workspaceId) { }
	private record ReservationFixture(UUID lotId) { }
	private record SkuFixture(UUID id, String catalogItemId, String unit) { }
}
