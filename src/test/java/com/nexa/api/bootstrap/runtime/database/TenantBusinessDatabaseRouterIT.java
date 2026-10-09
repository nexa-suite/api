package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogFamilySkuMappingLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceSeedItemRecord;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceSeedLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceSeedValidator;
import com.nexa.api.shared.context.RlsRequestScope;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
	private static final String CENTRAL_RUNTIME_PASSWORD = "central-runtime-only-test-password";
	private static final String TENANT_A_RUNTIME_PASSWORD = "tenant-a-runtime-only-test-password";
	private static final String TENANT_B_RUNTIME_PASSWORD = "tenant-b-runtime-only-test-password";
	private static final String TENANT_POLICY_SNAPSHOT_WRITER_PASSWORD = "tenant-policy-snapshot-writer-test-password";
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

		Flyway.configure().dataSource(CENTRAL.getJdbcUrl(), CENTRAL.getUsername(), CENTRAL.getPassword())
				.locations("classpath:db/migration").load().migrate();
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
				setRequestScope(fixture.tenantB());
				router.inTransaction(tenantB, ignored -> null);
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
		JdbcTenantBusinessDatabaseAuthority delegate = new JdbcTenantBusinessDatabaseAuthority(
				accessContextUseCase(), new JdbcTenantBusinessDatabaseBindingRegistry(centralRuntime));
		AtomicBoolean substitutedClaim = new AtomicBoolean();
		var authority = (com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority) context -> {
			var binding = delegate.requireReadyBinding(context);
			if (substitutedClaim.compareAndSet(false, true)) setRequestScope(fixture.tenantB());
			return binding;
		};

		try (TenantBusinessDatabaseRouter router = new TenantBusinessDatabaseRouter(authority, pools, 2)) {
			setRequestScope(fixture.tenantA());
			insertProbe(router, tenantA, "verified-context-rls");
			setRequestScope(fixture.tenantA());
			assertThat(probeCount(router, tenantA)).isEqualTo(1);
			assertThat(countProbe(tenantAAdmin, "verified-context-rls")).isEqualTo(1);
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
		central.update("INSERT INTO tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
				membershipId.value(), workspaceId.value(), userId.value(), "INTERNAL", "ACTIVE", now, now);
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
			statement.execute("CREATE ROLE nexa_runtime LOGIN PASSWORD '" + runtimePassword + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
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
