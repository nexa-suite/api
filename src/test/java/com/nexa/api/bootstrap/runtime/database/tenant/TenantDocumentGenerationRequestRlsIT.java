package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies document-generation RLS for runtime and the real dedicated worker router. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantDocumentGenerationRequestRlsIT {
	private static final String DATABASE_NAME = "tenant_document_rls";
	private static final String DATABASE_PASSWORD = "tenant-document-admin-test-only";
	private static final String MIGRATOR = "nexa_migrator";
	private static final String MIGRATOR_PASSWORD = "tenant-document-migrator-test-only";
	private static final String RUNTIME = "nexa_runtime";
	private static final String RUNTIME_PASSWORD = "tenant-document-runtime-test-only";
	private static final String DOCUMENTS_WORKER = "nexa_business_documents_worker";
	private static final String DOCUMENTS_WORKER_PASSWORD = "tenant-document-worker-test-only";
	private static final String TRACEABILITY_WORKER_PASSWORD = "tenant-document-traceability-test-only";
	private static final String PAYMENTS_WORKER_PASSWORD = "tenant-document-payments-test-only";
	private static final String POLICY_WRITER = "nexa_policy_snapshot_writer";
	private static final String TRACEABILITY_WORKER = "nexa_business_traceability_worker";
	private static final String PAYMENTS_WORKER = "nexa_payments_worker";
	private static final String RESERVATION_EXPIRY_WORKER = "nexa_warehouse_reservation_expiry_worker";
	private static final UUID TENANT_ID = UUID.fromString("c6694f66-50be-4b58-83ce-e4dd7ec81e55");
	private static final UUID WORKSPACE_A = UUID.fromString("24cac107-30ca-4432-bd73-3360e9e2580e");
	private static final UUID WORKSPACE_B = UUID.fromString("c00c8496-9c22-4105-9a9e-52b2da3d99c0");
	private static final UUID DATABASE_ID = UUID.fromString("a06d4ff8-85f5-4f73-88fd-0b9610da4f29");
	private static final UUID REQUEST_A = UUID.fromString("5994d58d-994c-4cc3-bf2f-04d0d876d77d");
	private static final UUID REQUEST_B = UUID.fromString("5a5002a4-57a4-46ea-bc1d-69d6491d0bdb");
	private static final UUID PAYMENT_REQUEST_A = UUID.fromString("ab2cc8f5-3cc3-4ed2-a3c4-25e44e28b821");
	private static final UUID PAYMENT_REQUEST_B = UUID.fromString("af54982f-0ce8-470b-8e7e-1b0ab87e4a6c");
	private static final UUID TRACEABILITY_REQUEST_A = UUID.fromString("d33ae2e8-7f2c-47d4-9db1-e650635270c5");
	private static final UUID TRACEABILITY_REQUEST_B = UUID.fromString("85851d84-ffac-46f3-8863-aefc2d624777");
	private static final String CREDENTIAL_REFERENCE = "local-tenant-business-documents-worker:" + TENANT_ID;

	@Container
	private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.4-alpine")
			.withDatabaseName(DATABASE_NAME)
			.withUsername("postgres")
			.withPassword(DATABASE_PASSWORD);

	@BeforeAll
	static void migrateAndSeedTenantDatabase() throws Exception {
		provisionRoles();
		Flyway.configure().dataSource(DATABASE.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
				.locations("filesystem:" + Path.of("src/main/resources/db/tenant-migration").toAbsolutePath().normalize())
				.load().migrate();

		try (Connection admin = adminConnection();
			 PreparedStatement identity = admin.prepareStatement("""
				 INSERT INTO nexa_platform.tenant_business_database_identity(singleton, tenant_id, database_identity)
				 VALUES (TRUE, ?, ?)
				 """);
			 PreparedStatement anchor = admin.prepareStatement("""
				 INSERT INTO nexa_platform.tenant_workspace_scope_anchor(tenant_id, workspace_id)
				 VALUES (?, ?)
				 """)) {
			identity.setObject(1, TENANT_ID);
			identity.setObject(2, DATABASE_ID);
			identity.executeUpdate();
			for (UUID workspaceId : List.of(WORKSPACE_A, WORKSPACE_B)) {
				anchor.setObject(1, TENANT_ID);
				anchor.setObject(2, workspaceId);
				anchor.executeUpdate();
			}
			insertRequest(admin, REQUEST_A, WORKSPACE_A);
			insertRequest(admin, REQUEST_B, WORKSPACE_B);
		}
	}

	@Test
	void runtimeAndDocumentWorkerRemainInsideTheVerifiedTenantWorkspace() throws Exception {
		try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
			 ResultSet security = statement.executeQuery("""
				 SELECT relation.relrowsecurity, relation.relforcerowsecurity
				 FROM pg_class relation
				 JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
				 WHERE schema_row.nspname = 'business_documents'
				   AND relation.relname = 'document_generation_request'
				 """)) {
			assertThat(security.next()).isTrue();
			assertThat(security.getBoolean(1)).isTrue();
			assertThat(security.getBoolean(2)).isTrue();
		}

		try (Connection runtime = runtimeConnection()) {
			runtime.setAutoCommit(false);
			setScope(runtime, TENANT_ID, WORKSPACE_A);
			assertThat(requestIds(runtime)).containsExactly(REQUEST_A);
			assertThat(updateLastError(runtime, REQUEST_A)).isEqualTo(1);
			assertThat(updateLastError(runtime, REQUEST_B))
					.as("runtime cannot write a request owned by another Workspace")
					.isZero();
			runtime.commit();
		}

		try (Connection runtime = runtimeConnection()) {
			runtime.setAutoCommit(false);
			setScope(runtime, TENANT_ID, WORKSPACE_A);
			assertThatThrownBy(() -> insertRequest(runtime, UUID.randomUUID(), WORKSPACE_B))
					.as("WITH CHECK rejects a request with a foreign Workspace")
					.isInstanceOf(SQLException.class)
					.satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
			runtime.rollback();
		}

		TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
		TenantBusinessDatabaseBinding binding = new TenantBusinessDatabaseBinding(new TenantId(TENANT_ID), DATABASE_ID,
				CREDENTIAL_REFERENCE, requirements.schemaManifestDigest());
		TenantBusinessDatabaseBindingRegistry bindings = ignored -> Optional.of(binding);
		TenantBusinessDocumentWorkerScopeQuery scopes = new TenantBusinessDocumentWorkerScopeQuery() {
			@Override
			public List<Scope> listReadyWorkspaces(UUID afterTenantId, UUID afterWorkspaceId, int limit) {
				return List.of(new Scope(TENANT_ID, WORKSPACE_A), new Scope(TENANT_ID, WORKSPACE_B));
			}

			@Override
			public boolean isReadyWorkspace(UUID tenantId, UUID workspaceId) {
				return TENANT_ID.equals(tenantId)
						&& (WORKSPACE_A.equals(workspaceId) || WORKSPACE_B.equals(workspaceId));
			}
		};
		HikariTenantBusinessDatabaseDataSourceFactory dataSources = new HikariTenantBusinessDatabaseDataSourceFactory(
				reference -> {
					if (!CREDENTIAL_REFERENCE.equals(reference)) {
						throw new IllegalArgumentException("Unexpected test worker credential reference");
					}
					return new TenantBusinessDatabaseCredentials(DATABASE.getJdbcUrl(), DOCUMENTS_WORKER,
							DOCUMENTS_WORKER_PASSWORD);
				}, 1);

		WorkerResult workerResult;
		try (TenantBusinessDatabaseDocumentWorkerRouter router = new TenantBusinessDatabaseDocumentWorkerRouter(
				bindings, dataSources, scopes, 1)) {
			workerResult = router.inTransaction(TENANT_ID, WORKSPACE_A, tenantJdbc -> {
				List<UUID> pending = tenantJdbc.query("""
						SELECT id FROM business_documents.document_generation_request
						WHERE status = 'PENDING' ORDER BY id
						""", (row, index) -> row.getObject(1, UUID.class));
				int claimed = tenantJdbc.update("""
						UPDATE business_documents.document_generation_request
						SET status = 'PROCESSING', attempt_count = attempt_count + 1,
						    processing_started_at = current_timestamp,
						    lease_until = current_timestamp + interval '2 minutes', claim_token = ?
						WHERE id = ? AND status = 'PENDING'
						""", UUID.randomUUID(), REQUEST_A);
				int foreignWorkspaceMutation = tenantJdbc.update("""
						UPDATE business_documents.document_generation_request
						SET status = 'FAILED'
						WHERE id = ?
						""", REQUEST_B);
				return new WorkerResult(pending, claimed, foreignWorkspaceMutation);
			});
		}

		assertThat(workerResult.pendingRequests()).containsExactly(REQUEST_A);
		assertThat(workerResult.claimedRequests()).isEqualTo(1);
		assertThat(workerResult.foreignWorkspaceMutation()).isZero();
		assertThat(requestStatus(REQUEST_A)).isEqualTo("PROCESSING");
		assertThat(requestStatus(REQUEST_B)).isEqualTo("PENDING");

		verifyPaymentsWorkerScope();
		verifyTraceabilityWorkerScope();
	}

	private static void verifyPaymentsWorkerScope() throws Exception {
		try (Connection worker = scopedWorkerConnection(PAYMENTS_WORKER, PAYMENTS_WORKER_PASSWORD)) {
			worker.setAutoCommit(false);
			insertRequest(worker, PAYMENT_REQUEST_A, WORKSPACE_A);
			worker.commit();
		}

		try (Connection worker = scopedWorkerConnection(PAYMENTS_WORKER, PAYMENTS_WORKER_PASSWORD)) {
			worker.setAutoCommit(false);
			assertThatThrownBy(() -> insertRequest(worker, PAYMENT_REQUEST_B, WORKSPACE_B))
					.as("payment worker cannot enqueue a request for another Workspace")
					.isInstanceOf(SQLException.class)
					.satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
			worker.rollback();
		}

		try (Connection worker = scopedWorkerConnection(PAYMENTS_WORKER, PAYMENTS_WORKER_PASSWORD)) {
			worker.setAutoCommit(false);
			assertThatThrownBy(() -> requestIds(worker))
					.as("payment worker retains its insert-only queue grant")
					.isInstanceOf(SQLException.class)
					.satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
			worker.rollback();
		}
	}

	private static void verifyTraceabilityWorkerScope() throws Exception {
		try (Connection worker = scopedWorkerConnection(TRACEABILITY_WORKER, TRACEABILITY_WORKER_PASSWORD)) {
			worker.setAutoCommit(false);
			assertThat(requestIds(worker)).contains(REQUEST_A, PAYMENT_REQUEST_A).doesNotContain(REQUEST_B);
			insertRequest(worker, TRACEABILITY_REQUEST_A, WORKSPACE_A);
			worker.commit();
		}

		try (Connection worker = scopedWorkerConnection(TRACEABILITY_WORKER, TRACEABILITY_WORKER_PASSWORD)) {
			worker.setAutoCommit(false);
			assertThatThrownBy(() -> insertRequest(worker, TRACEABILITY_REQUEST_B, WORKSPACE_B))
					.as("traceability worker cannot enqueue a request for another Workspace")
					.isInstanceOf(SQLException.class)
					.satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
			worker.rollback();
		}

		try (Connection worker = scopedWorkerConnection(TRACEABILITY_WORKER, TRACEABILITY_WORKER_PASSWORD)) {
			worker.setAutoCommit(false);
			assertThatThrownBy(() -> updateLastError(worker, REQUEST_B))
					.as("traceability worker retains its existing non-mutation grant")
					.isInstanceOf(SQLException.class)
					.satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
			worker.rollback();
		}
	}

	private static void provisionRoles() throws SQLException {
		try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
			statement.execute("CREATE ROLE " + MIGRATOR + " LOGIN NOINHERIT PASSWORD '" + MIGRATOR_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE " + RUNTIME + " LOGIN NOINHERIT PASSWORD '" + RUNTIME_PASSWORD
					+ "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE " + POLICY_WRITER + " LOGIN PASSWORD 'tenant-document-policy-test-only'");
			statement.execute("CREATE ROLE " + DOCUMENTS_WORKER + " LOGIN PASSWORD '" + DOCUMENTS_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE " + PAYMENTS_WORKER + " LOGIN PASSWORD '" + PAYMENTS_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE " + TRACEABILITY_WORKER
					+ " LOGIN PASSWORD '" + TRACEABILITY_WORKER_PASSWORD
					+ "' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("CREATE ROLE " + RESERVATION_EXPIRY_WORKER + " NOLOGIN NOINHERIT "
					+ "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
			statement.execute("GRANT CONNECT, CREATE ON DATABASE \"" + DATABASE_NAME + "\" TO " + MIGRATOR);
			statement.execute("GRANT CONNECT ON DATABASE \"" + DATABASE_NAME + "\" TO " + RUNTIME + ", "
					+ DOCUMENTS_WORKER + ", " + POLICY_WRITER + ", " + PAYMENTS_WORKER + ", " + TRACEABILITY_WORKER);
			statement.execute("GRANT CREATE ON SCHEMA public TO " + MIGRATOR);
		}
	}

	private static void insertRequest(Connection connection, UUID id, UUID workspaceId) throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO business_documents.document_generation_request
				    (id, tenant_id, workspace_id, requested_by_membership_id, subject_type, subject_id,
				     document_type, format, status, idempotency_key, request_hash, requested_at)
				VALUES (?, ?, ?, ?, 'SALES_ORDER', ?, 'ORDER_SUMMARY', 'PDF', 'PENDING', ?, ?, current_timestamp)
				""")) {
			insert.setObject(1, id);
			insert.setObject(2, TENANT_ID);
			insert.setObject(3, workspaceId);
			insert.setObject(4, UUID.randomUUID());
			insert.setObject(5, UUID.randomUUID());
			insert.setString(6, "request-" + id);
			insert.setString(7, "a".repeat(64));
			insert.executeUpdate();
		}
	}

	private static List<UUID> requestIds(Connection connection) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement("""
				SELECT id FROM business_documents.document_generation_request ORDER BY id
				"""); ResultSet rows = query.executeQuery()) {
			java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
			while (rows.next()) ids.add(rows.getObject(1, UUID.class));
			return List.copyOf(ids);
		}
	}

	private static int updateLastError(Connection connection, UUID requestId) throws SQLException {
		try (PreparedStatement update = connection.prepareStatement("""
				UPDATE business_documents.document_generation_request SET last_error = 'scoped runtime write'
				WHERE id = ?
				""")) {
			update.setObject(1, requestId);
			return update.executeUpdate();
		}
	}

	private static void setScope(Connection connection, UUID tenantId, UUID workspaceId) throws SQLException {
		try (PreparedStatement scope = connection.prepareStatement("""
				SELECT set_config('app.current_tenant_id', ?, true),
				       set_config('app.current_workspace_id', ?, true)
				""")) {
			scope.setString(1, tenantId.toString());
			scope.setString(2, workspaceId.toString());
			scope.execute();
		}
	}

	private static String requestStatus(UUID requestId) throws SQLException {
		try (Connection admin = adminConnection(); PreparedStatement query = admin.prepareStatement("""
				SELECT status FROM business_documents.document_generation_request WHERE id = ?
				""")) {
			query.setObject(1, requestId);
			try (ResultSet result = query.executeQuery()) {
				if (!result.next()) throw new AssertionError("Expected seeded generation request");
				return result.getString(1);
			}
		}
	}

	private static Connection adminConnection() throws SQLException {
		return DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
	}

	private static Connection runtimeConnection() throws SQLException {
		return DriverManager.getConnection(DATABASE.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
	}

	private static Connection scopedWorkerConnection(String username, String password) throws SQLException {
		DriverManagerDataSource delegate = new DriverManagerDataSource(DATABASE.getJdbcUrl(), username, password);
		return RlsScopedDataSource.forVerifiedSystemTenant(delegate,
				new TenantId(TENANT_ID), new WorkspaceId(WORKSPACE_A)).getConnection();
	}

	private record WorkerResult(List<UUID> pendingRequests, int claimedRequests, int foreignWorkspaceMutation) { }
}
