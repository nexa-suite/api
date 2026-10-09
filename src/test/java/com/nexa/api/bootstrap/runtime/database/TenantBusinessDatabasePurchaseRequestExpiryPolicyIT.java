package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.database.tenant.DriverManagerTenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.TenantExternalConfigurationSource;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service.TenantOperationalSettingsIntegrationService;
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
import com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc.JdbcTenantConfigurationAdapter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-PostgreSQL rehearsal of the opt-in BC01-to-Tenant expiry-policy projection. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantBusinessDatabasePurchaseRequestExpiryPolicyIT {
	private static final String RUNTIME_USERNAME = "nexa_runtime";
	private static final String RUNTIME_PASSWORD = "tenant-expiry-runtime-test-password";
	private static final String WRITER_USERNAME = "nexa_policy_snapshot_writer";
	private static final String WRITER_PASSWORD = "tenant-expiry-writer-test-password";
	private static final UUID TENANT_A_DATABASE_ID = UUID.fromString("dd65ac46-47a8-4986-9ac1-00f80153dc4b");
	private static final UUID TENANT_B_DATABASE_ID = UUID.fromString("e2dd4fd6-18cb-4a2b-9d2b-1207fdcb1c38");
	private static final TenantRecord TENANT_A = new TenantRecord(
			UUID.fromString("4bbf137d-42d8-4a63-a153-90d3e96a6d17"),
			UUID.fromString("09cc788d-7392-49b4-aeb3-86deaf9a598f"),
			TENANT_A_DATABASE_ID, "expiry-runtime-a");
	private static final TenantRecord TENANT_B = new TenantRecord(
			UUID.fromString("e3bbeb4a-28d4-4517-bc9c-67fb56bd3a81"),
			UUID.fromString("13e6fbfe-d4e9-4aa4-9d11-488f726049a1"),
			TENANT_B_DATABASE_ID, "expiry-runtime-b");

	@Container
	private static final PostgreSQLContainer CENTRAL = container("nexa-expiry-central");
	@Container
	private static final PostgreSQLContainer TENANT_A_DB = container("nexa-expiry-tenant-a");
	@Container
	private static final PostgreSQLContainer TENANT_B_DB = container("nexa-expiry-tenant-b");

	private static JdbcTemplate centralAdmin;
	private static JdbcTemplate tenantAAdmin;
	private static JdbcTemplate tenantBAdmin;
	private static OperationalSettingsAccess centralSettings;
	private static TenantBusinessDatabaseRouter router;
	private static TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver resolver;

	@BeforeAll
	static void provisionAndWireOptInFixture() {
		createTenantRoles(TENANT_A_DB);
		createTenantRoles(TENANT_B_DB);
		Flyway.configure().dataSource(CENTRAL.getJdbcUrl(), CENTRAL.getUsername(), CENTRAL.getPassword())
				.locations("classpath:db/migration").target("146").load().migrate();
		Flyway.configure().dataSource(TENANT_A_DB.getJdbcUrl(), TENANT_A_DB.getUsername(), TENANT_A_DB.getPassword())
				.locations("classpath:db/tenant-migration").load().migrate();
		Flyway.configure().dataSource(TENANT_B_DB.getJdbcUrl(), TENANT_B_DB.getUsername(), TENANT_B_DB.getPassword())
				.locations("classpath:db/tenant-migration").load().migrate();

		centralAdmin = adminJdbc(CENTRAL);
		tenantAAdmin = adminJdbc(TENANT_A_DB);
		tenantBAdmin = adminJdbc(TENANT_B_DB);
		seedCentralWorkspace(TENANT_A);
		seedCentralWorkspace(TENANT_B);
		seedTenantScope(tenantAAdmin, TENANT_A);
		seedTenantScope(tenantBAdmin, TENANT_B);
		seedClientAccount(tenantAAdmin, TENANT_A, "expiry-account-a");
		seedClientAccount(tenantBAdmin, TENANT_B, "expiry-account-b");

		var configuration = new JdbcTenantConfigurationAdapter(centralAdmin, EMPTY_EXTERNAL_CONFIGURATION);
		centralSettings = new TenantOperationalSettingsIntegrationService(configuration);
		Map<UUID, TenantRecord> tenants = Map.of(TENANT_A.tenantId(), TENANT_A, TENANT_B.tenantId(), TENANT_B);
		Map<String, TenantRecord> runtimeSecrets = Map.of(
				TENANT_A.runtimeSecretReference(), TENANT_A, TENANT_B.runtimeSecretReference(), TENANT_B);
		Map<UUID, TenantBusinessDatabaseBinding> bindings = Map.of(
				TENANT_A.tenantId(), new TenantBusinessDatabaseBinding(new TenantId(TENANT_A.tenantId()),
						TENANT_A.databaseIdentity(), TENANT_A.runtimeSecretReference()),
				TENANT_B.tenantId(), new TenantBusinessDatabaseBinding(new TenantId(TENANT_B.tenantId()),
						TENANT_B.databaseIdentity(), TENANT_B.runtimeSecretReference()));
		TenantBusinessDatabaseAuthority authority = context -> {
			TenantBusinessDatabaseBinding binding = bindings.get(context.tenantId().value());
			if (binding == null || !binding.tenantId().equals(context.tenantId())) {
				throw new IllegalStateException("Tenant database binding is unavailable");
			}
			return binding;
		};
		var runtimeFactory = new HikariTenantBusinessDatabaseDataSourceFactory(reference -> {
			TenantRecord tenant = runtimeSecrets.get(reference);
			if (tenant == null) throw new IllegalStateException("Runtime test credentials are unavailable");
			return credentials(tenant, RUNTIME_USERNAME, RUNTIME_PASSWORD);
		}, 2);
		router = new TenantBusinessDatabaseRouter(authority, runtimeFactory, 2);
		var writerFactory = new DriverManagerTenantBusinessDatabasePolicySnapshotWriterDataSourceFactory((tenantId, identity) -> {
			TenantRecord tenant = tenants.get(tenantId.value());
			if (tenant == null || !tenant.databaseIdentity().equals(identity)) {
				throw new IllegalStateException("Dedicated writer credentials are unavailable for this Tenant database");
			}
			return credentials(tenant, WRITER_USERNAME, WRITER_PASSWORD);
		});
		resolver = new TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver(
				centralSettings, router, authority, writerFactory);
	}

	@Test
	void centralVersionsConfirmedAbsenceAndWriterIsolationControlPurchaseRequestExpiry() {
		CurrentAccessContext contextA = context(TENANT_A);
		CurrentAccessContext contextB = context(TENANT_B);
		clearFixtureState();

		for (int days = 1; days <= 7; days++) {
			if (days == 1) insertCentralOperationalSettings(TENANT_A, days, 0);
			else updateCentralExpiryDays(TENANT_A, days);
			Instant expectedAt = Instant.parse("2026-10-01T12:00:00Z").plus(days, ChronoUnit.DAYS);
			Instant expiresAt = insertSubmittedRequest(resolver, contextA, UUID.randomUUID());
			assertThat(expiresAt).isEqualTo(expectedAt);
		}

		OperationalSettingsAccess.PurchaseRequestExpiryPolicySource absent = centralSettings
				.findPurchaseRequestExpiryPolicy(new TenantId(TENANT_B.tenantId()), new WorkspaceId(TENANT_B.workspaceId()))
				.orElseThrow();
		assertThat(absent.sourceState()).isEqualTo(OperationalSettingsAccess.SourceState.CONFIRMED_ABSENT);
		assertThat(absent.sourceVersion()).isNull();
		assertThat(absent.expiryDays()).isEqualTo(3);
		Instant absentFallbackExpiry = insertSubmittedRequest(resolver, contextB, UUID.randomUUID());
		assertThat(absentFallbackExpiry).isEqualTo(Instant.parse("2026-10-01T12:00:00Z").plus(3, ChronoUnit.DAYS));
		long absentRevision = tenantBAdmin.queryForObject("SELECT snapshot_revision FROM nexa_platform.purchase_request_expiry_policy_snapshot WHERE tenant_id=? AND workspace_id=?",
				Long.class, TENANT_B.tenantId(), TENANT_B.workspaceId());
		assertThat(absentRevision).isEqualTo(1L);

		insertCentralOperationalSettings(TENANT_B, 6, 0);
		Instant presentExpiry = insertSubmittedRequest(resolver, contextB, UUID.randomUUID());
		assertThat(presentExpiry).isEqualTo(Instant.parse("2026-10-01T12:00:00Z").plus(6, ChronoUnit.DAYS));
		long presentRevision = tenantBAdmin.queryForObject("SELECT snapshot_revision FROM nexa_platform.purchase_request_expiry_policy_snapshot WHERE tenant_id=? AND workspace_id=?",
				Long.class, TENANT_B.tenantId(), TENANT_B.workspaceId());
		assertThat(presentRevision).isEqualTo(2L);

		assertThat(tenantAAdmin.queryForObject("SELECT expiry_days FROM nexa_platform.purchase_request_expiry_policy_snapshot WHERE tenant_id=? AND workspace_id=?",
				Integer.class, TENANT_A.tenantId(), TENANT_A.workspaceId())).isEqualTo(7);
		assertThat(tenantBAdmin.queryForObject("SELECT expiry_days FROM nexa_platform.purchase_request_expiry_policy_snapshot WHERE tenant_id=? AND workspace_id=?",
				Integer.class, TENANT_B.tenantId(), TENANT_B.workspaceId())).isEqualTo(6);
		assertThat(tenantAAdmin.queryForObject("SELECT count(*) FROM nexa_platform.purchase_request_expiry_policy_snapshot", Integer.class)).isEqualTo(1);
		assertThat(tenantBAdmin.queryForObject("SELECT count(*) FROM nexa_platform.purchase_request_expiry_policy_snapshot", Integer.class)).isEqualTo(1);
		assertRolePrivileges(tenantAAdmin);

		assertThatThrownBy(() -> router.inTransaction(contextA, jdbc -> insertRequest(jdbc, contextA, UUID.randomUUID())))
				.hasMessageContaining("not centrally validated");
		assertThatThrownBy(() -> resolver.inTransaction(contextA, jdbc -> {
			jdbc.queryForObject("SELECT set_config('app.expected_purchase_request_expiry_policy_revision', ?, true)",
					String.class, "6");
			return insertRequest(jdbc, contextA, UUID.randomUUID());
		})).hasMessageContaining("snapshot is missing or stale");

		CurrentAccessContext invalidWorkspace = context(TENANT_A.tenantId(), UUID.randomUUID());
		assertThatThrownBy(() -> resolver.inTransaction(invalidWorkspace,
				jdbc -> insertRequest(jdbc, invalidWorkspace, UUID.randomUUID())))
				.isInstanceOf(com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotConflictException.class);
	}

	private static void assertRolePrivileges(JdbcTemplate tenantAdmin) {
		assertThat(tenantAdmin.queryForObject("SELECT has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'SELECT')",
				Boolean.class)).isTrue();
		for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
			assertThat(tenantAdmin.queryForObject("SELECT has_table_privilege('nexa_runtime', 'nexa_platform.purchase_request_expiry_policy_snapshot', ?)",
					Boolean.class, privilege)).as("runtime %s remains denied on policy snapshots", privilege).isFalse();
		}
		for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
			assertThat(tenantAdmin.queryForObject("SELECT has_table_privilege('nexa_policy_snapshot_writer', 'nexa_platform.purchase_request_expiry_policy_snapshot', ?)",
					Boolean.class, privilege)).as("dedicated writer %s is granted", privilege).isTrue();
		}
		assertThat(tenantAdmin.queryForObject("SELECT has_table_privilege('nexa_policy_snapshot_writer', 'nexa_platform.purchase_request_expiry_policy_snapshot', 'DELETE')",
				Boolean.class)).isFalse();
		assertThat(tenantAdmin.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname='nexa_policy_snapshot_writer'",
				Boolean.class)).isFalse();
	}

	private static Instant insertSubmittedRequest(TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver policyResolver,
			CurrentAccessContext context, UUID requestId) {
		return policyResolver.inTransaction(context, jdbc -> insertRequest(jdbc, context, requestId));
	}

	private static Instant insertRequest(JdbcTemplate jdbc, CurrentAccessContext context, UUID requestId) {
		Instant createdAt = Instant.parse("2026-10-01T12:00:00Z");
		UUID accountId = UUID.nameUUIDFromBytes((context.tenantId().toString() + ":account").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		jdbc.update("""
				INSERT INTO sales.purchase_request
				    (id, tenant_id, workspace_id, client_account_id, buyer_membership_id, code, status, priority,
				     created_at, updated_at, submitted_at)
				VALUES (?, ?, ?, ?, ?, ?, 'SUBMITTED', 'NORMAL', ?, ?, ?)
				""", requestId, context.tenantId().value(), context.workspaceId().value(), accountId,
				UUID.randomUUID(), "EXP-" + requestId.toString().substring(0, 8), Timestamp.from(createdAt),
				Timestamp.from(createdAt), Timestamp.from(createdAt));
		return jdbc.queryForObject("SELECT expires_at FROM sales.purchase_request WHERE id=?",
				(rs, row) -> rs.getTimestamp(1).toInstant(), requestId);
	}

	private static void clearFixtureState() {
		centralAdmin.update("DELETE FROM tenant_management.operational_settings WHERE workspace_id IN (?, ?)",
				TENANT_A.workspaceId(), TENANT_B.workspaceId());
		for (var tenant : List.of(Map.entry(TENANT_A, tenantAAdmin), Map.entry(TENANT_B, tenantBAdmin))) {
			tenant.getValue().update("DELETE FROM sales.purchase_request WHERE tenant_id=? AND workspace_id=?",
					tenant.getKey().tenantId(), tenant.getKey().workspaceId());
			tenant.getValue().update("DELETE FROM nexa_platform.purchase_request_expiry_policy_snapshot WHERE tenant_id=? AND workspace_id=?",
					tenant.getKey().tenantId(), tenant.getKey().workspaceId());
		}
	}

	private static void updateCentralExpiryDays(TenantRecord tenant, int days) {
		centralAdmin.update("UPDATE tenant_management.operational_settings SET purchase_request_expiry_days=?, version=version+1, updated_at=current_timestamp WHERE workspace_id=?",
				days, tenant.workspaceId());
	}

	private static void insertCentralOperationalSettings(TenantRecord tenant, int days, long version) {
		centralAdmin.update("""
				INSERT INTO tenant_management.operational_settings
				    (workspace_id, purchase_request_expiry_days, version, updated_at)
				VALUES (?, ?, ?, current_timestamp)
				""", tenant.workspaceId(), days, version);
	}

	private static void seedCentralWorkspace(TenantRecord tenant) {
		Timestamp now = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
		centralAdmin.update("INSERT INTO tenant_management.tenant(id,name,slug,status,created_at,updated_at) VALUES (?,?,?,?,?,?)",
			tenant.tenantId(), "Expiry Tenant " + tenant.tenantId(), "expiry-" + tenant.tenantId(), "ACTIVE", now, now);
		centralAdmin.update("INSERT INTO tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
			tenant.workspaceId(), tenant.tenantId(), "Expiry Workspace", "primary", "ACTIVE", now, now);
	}

	private static void seedTenantScope(JdbcTemplate tenantAdmin, TenantRecord tenant) {
		tenantAdmin.update("INSERT INTO nexa_platform.tenant_business_database_identity(singleton,tenant_id,database_identity) VALUES (true,?,?)",
			tenant.tenantId(), tenant.databaseIdentity());
		tenantAdmin.update("INSERT INTO nexa_platform.tenant_workspace_scope_anchor(tenant_id,workspace_id) VALUES (?,?)",
			tenant.tenantId(), tenant.workspaceId());
	}

	private static void seedClientAccount(JdbcTemplate tenantAdmin, TenantRecord tenant, String code) {
		Timestamp now = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
		UUID accountId = UUID.nameUUIDFromBytes((tenant.tenantId() + ":account").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		tenantAdmin.update("""
				INSERT INTO sales.client_account
				    (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,
				     tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,
				     status,created_at,updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
				""", accountId, tenant.tenantId(), tenant.workspaceId(), code, "Expiry Account", "Expiry Account",
				"PE", "RUC", code, "WHOLESALE", "Test Contact", "expiry@example.test", "000000000",
				"STANDARD", "CREDIT_30", "ACTIVE", now, now);
	}

	private static CurrentAccessContext context(TenantRecord tenant) {
		return context(tenant.tenantId(), tenant.workspaceId());
	}

	private static CurrentAccessContext context(UUID tenantId, UUID workspaceId) {
		Membership membership = new Membership(new MembershipId(UUID.randomUUID()), new UserId(UUID.randomUUID()),
				new TenantId(tenantId), new WorkspaceId(workspaceId), Set.of(MembershipRole.SALES),
				MembershipStatus.ACTIVE);
		return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE, WorkspaceStatus.ACTIVE),
				Surface.PLATFORM);
	}

	private static TenantBusinessDatabaseCredentials credentials(TenantRecord tenant, String username, String password) {
		return new TenantBusinessDatabaseCredentials(containerFor(tenant).getJdbcUrl(), username, password);
	}

	private static PostgreSQLContainer containerFor(TenantRecord tenant) {
		return tenant.tenantId().equals(TENANT_A.tenantId()) ? TENANT_A_DB : TENANT_B_DB;
	}

	private static void createTenantRoles(PostgreSQLContainer container) {
		try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("CREATE ROLE nexa_runtime LOGIN PASSWORD '" + RUNTIME_PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE nexa_policy_snapshot_writer LOGIN PASSWORD '" + WRITER_PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("GRANT CONNECT ON DATABASE \"" + container.getDatabaseName() + "\" TO nexa_runtime, nexa_policy_snapshot_writer");
		} catch (Exception exception) {
			throw new IllegalStateException("Could not provision distinct Tenant runtime and snapshot-writer roles", exception);
		}
	}

	private static JdbcTemplate adminJdbc(PostgreSQLContainer container) {
		return new JdbcTemplate(adminDataSource(container));
	}

	private static DataSource adminDataSource(PostgreSQLContainer container) {
		var dataSource = new DriverManagerDataSource();
		dataSource.setUrl(container.getJdbcUrl());
		dataSource.setUsername(container.getUsername());
		dataSource.setPassword(container.getPassword());
		return dataSource;
	}

	private static PostgreSQLContainer container(String databaseName) {
		return new PostgreSQLContainer("postgres:18.4-alpine")
				.withDatabaseName(databaseName).withUsername("nexa_admin").withPassword("test-admin-only-password");
	}

	private static final TenantExternalConfigurationSource EMPTY_EXTERNAL_CONFIGURATION = new TenantExternalConfigurationSource() {
		@Override public List<Preference> notificationPreferences(UUID workspaceId) { return List.of(); }
		@Override public Preference validateNotificationPreference(Preference preference) { return preference; }
		@Override public long notificationVersion(UUID workspaceId) { return 0; }
		@Override public int updateNotificationPreference(UUID workspaceId, Preference preference) { return 0; }
		@Override public void ensureNotificationDefaults(UUID workspaceId) { }
		@Override public long salesTransactionCount(UUID tenantId) { return 0; }
	};

	private record TenantRecord(UUID tenantId, UUID workspaceId, UUID databaseIdentity,
			String runtimeSecretReference) { }
}
