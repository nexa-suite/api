package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.BaseStream;

/** Routes synchronous document-worker work through its dedicated, least-privilege Tenant role. */
public final class TenantBusinessDatabaseDocumentWorkerRouter implements TenantBusinessDocumentWorkerRouter, AutoCloseable {
	private static final String WORKER_ROLE = "nexa_business_documents_worker";
	private static final String CREDENTIAL_REFERENCE_PREFIX = "local-tenant-business-documents-worker:";

	private final TenantBusinessDatabaseBindingRegistry bindings;
	private final TenantBusinessDatabasePoolRegistry pools;
	private final TenantBusinessDocumentWorkerScopeQuery scopes;

	public TenantBusinessDatabaseDocumentWorkerRouter(TenantBusinessDatabaseBindingRegistry bindings,
			TenantBusinessDatabaseDataSourceFactory dataSources, TenantBusinessDocumentWorkerScopeQuery scopes,
			int maximumCachedPools) {
		this.bindings = Objects.requireNonNull(bindings, "Central Tenant binding registry is required");
		this.pools = new TenantBusinessDatabasePoolRegistry(Objects.requireNonNull(dataSources), maximumCachedPools);
		this.scopes = Objects.requireNonNull(scopes, "READY Tenant workspace scope query is required");
	}

	@Override
	public <T> T inTransaction(UUID tenantIdValue, UUID workspaceIdValue, Function<JdbcTemplate, T> work) {
		TenantId tenantId = new TenantId(Objects.requireNonNull(tenantIdValue, "Tenant scope is required"));
		WorkspaceId workspaceId = new WorkspaceId(Objects.requireNonNull(workspaceIdValue, "Workspace scope is required"));
		Objects.requireNonNull(work, "Synchronous Tenant document work is required");
		TenantBusinessDatabaseRouter.ensureRouteCanStart();

		RlsRequestScope.Scope previousScope = RlsRequestScope.current();
		boolean previousCrossScopeScan = RlsRequestScope.crossScopeWorkspaceScanEnabled();
		T result;
		try {
			RlsRequestScope.clearCrossScopeWorkspaceScan();
			RlsRequestScope.set(tenantId.value(), workspaceId.value());
			if (!scopes.isReadyWorkspace(tenantId.value(), workspaceId.value())) {
				throw new TenantBusinessDatabaseUnavailableException(
						"Tenant document work requires a READY Tenant database and Workspace anchor");
			}
			TenantBusinessDatabaseBinding centralBinding = readyBinding(tenantId);
			TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
			requirements.verifyRequiredSqlAssets();
			if (centralBinding.verifiedSchemaManifestSha256() == null
					|| !requirements.schemaManifestDigest().equals(centralBinding.verifiedSchemaManifestSha256())) {
				pools.retireTenant(tenantId);
				throw new TenantBusinessDatabaseSchemaManifestMismatchException();
			}

			TenantBusinessDatabaseBinding workerBinding = new TenantBusinessDatabaseBinding(tenantId,
					centralBinding.databaseIdentity(), CREDENTIAL_REFERENCE_PREFIX + tenantId.value(),
					centralBinding.verifiedSchemaManifestSha256());
			try (TenantBusinessDatabasePoolRegistry.Lease lease = pools.acquire(workerBinding)) {
				DataSource tenantDataSource = RlsScopedDataSource.forVerifiedSystemTenant(
						lease.dataSource(workspaceId), tenantId, workspaceId);
				TransactionTemplate transaction = new TransactionTemplate(
						new DataSourceTransactionManager(tenantDataSource));
				transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
				TenantBusinessDatabaseRouter.enterRoutedTransaction(workerBinding);
				try {
					result = transaction.execute(status -> {
						JdbcTemplate jdbc = new JdbcTemplate(tenantDataSource);
						verifyWorkerRole(jdbc);
						T value = work.apply(jdbc);
						rejectEscapedResource(value);
						return value;
					});
				} finally {
					TenantBusinessDatabaseRouter.exitRoutedTransaction();
				}
			}

			TenantBusinessDatabaseBinding currentBinding = readyBinding(tenantId);
			boolean workspaceStillReady = scopes.isReadyWorkspace(tenantId.value(), workspaceId.value());
			if (!sameReadyBinding(centralBinding, currentBinding) || !workspaceStillReady) {
				pools.retireTenant(tenantId);
				throw new TenantBusinessDatabaseUnavailableException(
						"Tenant document work completed while its READY binding or Workspace anchor changed");
			}
			return result;
		} catch (TenantBusinessDatabaseUnavailableException exception) {
			pools.retireTenant(tenantId);
			throw exception;
		} finally {
			RlsRequestScope.clear();
			if (previousScope != null) RlsRequestScope.set(previousScope.tenantId(), previousScope.workspaceId());
			if (previousCrossScopeScan) RlsRequestScope.enableCrossScopeWorkspaceScan();
		}
	}

	private TenantBusinessDatabaseBinding readyBinding(TenantId tenantId) {
		return bindings.findReadyBinding(tenantId).filter(binding -> binding.tenantId().equals(tenantId))
				.orElseThrow(() -> new TenantBusinessDatabaseUnavailableException(
						"No ready Tenant database is provisioned for document work"));
	}

	private static boolean sameReadyBinding(TenantBusinessDatabaseBinding expected,
			TenantBusinessDatabaseBinding actual) {
		return expected.tenantId().equals(actual.tenantId())
				&& expected.databaseIdentity().equals(actual.databaseIdentity())
				&& expected.credentialSecretReference().equals(actual.credentialSecretReference())
				&& Objects.equals(expected.verifiedSchemaManifestSha256(), actual.verifiedSchemaManifestSha256());
	}

	private static void verifyWorkerRole(JdbcTemplate jdbc) {
		String role = jdbc.queryForObject("select current_user", String.class);
		Boolean safeRole = jdbc.queryForObject("select rolcanlogin and not rolsuper and not rolcreatedb "
				+ "and not rolcreaterole and not rolreplication and not rolbypassrls "
				+ "and not exists (select 1 from pg_auth_members where member=pg_roles.oid or roleid=pg_roles.oid) "
				+ "and not exists (select 1 from pg_database where datdba=pg_roles.oid) "
				+ "and not exists (select 1 from pg_namespace where nspowner=pg_roles.oid) "
				+ "and not exists (select 1 from pg_class where relowner=pg_roles.oid) "
				+ "and not exists (select 1 from pg_proc where proowner=pg_roles.oid) "
				+ "and not exists (select 1 from pg_namespace where has_schema_privilege(pg_roles.oid, oid, 'CREATE')) "
				+ "from pg_roles where rolname=current_user", Boolean.class);
		if (!WORKER_ROLE.equals(role) || !Boolean.TRUE.equals(safeRole)) {
			throw new TenantBusinessDatabaseUnavailableException(
					"Tenant document worker requires its isolated least-privilege role");
		}
	}

	private static void rejectEscapedResource(Object result) {
		if (result instanceof JdbcTemplate || result instanceof DataSource || result instanceof Connection
				|| result instanceof Statement || result instanceof ResultSet || result instanceof Future<?>
				|| result instanceof CompletionStage<?> || result instanceof BaseStream<?, ?>) {
			throw new IllegalStateException("Tenant document work must not return JDBC resources or asynchronous results");
		}
	}

	@Override
	public void close() { pools.close(); }
}
