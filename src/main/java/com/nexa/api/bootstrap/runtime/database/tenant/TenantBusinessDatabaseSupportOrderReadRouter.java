package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrant;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrantValidationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.stream.BaseStream;

/** Read-only Tenant route for a server-issued, one-order support grant. */
public final class TenantBusinessDatabaseSupportOrderReadRouter implements AutoCloseable {
	private final TenantBusinessDatabaseBindingRegistry bindings;
	private final SupportOrderReadGrantValidationPort grants;
	private final TenantBusinessDatabasePoolRegistry pools;

	public TenantBusinessDatabaseSupportOrderReadRouter(TenantBusinessDatabaseBindingRegistry bindings,
			SupportOrderReadGrantValidationPort grants, TenantBusinessDatabaseDataSourceFactory dataSources,
			int maximumCachedPools) {
		this.bindings = Objects.requireNonNull(bindings);
		this.grants = Objects.requireNonNull(grants);
		this.pools = new TenantBusinessDatabasePoolRegistry(Objects.requireNonNull(dataSources), maximumCachedPools);
	}

	public <T> T read(SupportOrderReadGrant grant, TenantBusinessDatabaseWork<T> work) {
		Objects.requireNonNull(grant, "An approved support read grant is required");
		Objects.requireNonNull(work, "A bounded Tenant read is required");
		TenantId tenantId = new TenantId(grant.tenantId());
		WorkspaceId workspaceId = new WorkspaceId(grant.workspaceId());
		TenantBusinessDatabaseRouter.ensureRouteCanStart();
		RlsRequestScope.Scope priorScope = RlsRequestScope.current();
		boolean priorCrossScopeScan = RlsRequestScope.crossScopeWorkspaceScanEnabled();
		try {
			RlsRequestScope.set(tenantId.value(), workspaceId.value());
			if (!Instant.now().isBefore(grant.expiresAt()) || !grants.isActive(grant)) {
				throw new AccessDeniedException("The support order read grant is no longer active");
			}
			TenantBusinessDatabaseBinding binding = bindings.findReadyBinding(tenantId)
					.orElseThrow(() -> new TenantBusinessDatabaseUnavailableException(
							"No ready Tenant database is provisioned for this support read"));
			TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
			requirements.verifyRequiredSqlAssets();
			if (binding.verifiedSchemaManifestSha256() == null
					|| !requirements.schemaManifestDigest().equals(binding.verifiedSchemaManifestSha256())) {
				pools.retireTenant(tenantId);
				throw new TenantBusinessDatabaseSchemaManifestMismatchException();
			}
			T projection;
			try (TenantBusinessDatabasePoolRegistry.Lease lease = pools.acquire(binding)) {
				DataSource tenantDataSource = RlsScopedDataSource.forVerifiedSystemTenant(
						lease.dataSource(workspaceId), tenantId, workspaceId);
				TransactionTemplate transaction = new TransactionTemplate(
						new DataSourceTransactionManager(tenantDataSource));
				transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
				transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
				transaction.setReadOnly(true);
				TenantBusinessDatabaseRouter.enterRoutedTransaction(binding);
				try {
					projection = Objects.requireNonNull(transaction.execute(status -> {
						T result = work.execute(new JdbcTemplate(tenantDataSource));
						rejectEscapedResource(result);
						return result;
					}), "Tenant support read returned no projection");
				} finally {
					TenantBusinessDatabaseRouter.exitRoutedTransaction();
				}
			}
			if (!Instant.now().isBefore(grant.expiresAt()) || !grants.isActive(grant)) {
				throw new AccessDeniedException("The support order read grant changed while the read was in progress");
			}
			return projection;
		} catch (TenantBusinessDatabaseUnavailableException exception) {
			pools.retireTenant(tenantId);
			throw exception;
		} finally {
			RlsRequestScope.clear();
			if (priorScope != null) RlsRequestScope.set(priorScope.tenantId(), priorScope.workspaceId());
			if (priorCrossScopeScan) RlsRequestScope.enableCrossScopeWorkspaceScan();
		}
	}

	private static void rejectEscapedResource(Object result) {
		if (result instanceof JdbcTemplate || result instanceof DataSource || result instanceof Connection
				|| result instanceof Statement || result instanceof ResultSet || result instanceof Future<?>
				|| result instanceof CompletionStage<?> || result instanceof BaseStream<?, ?>) {
			throw new IllegalStateException("Tenant support reads must return a bounded projection, not a JDBC resource");
		}
	}

	@Override
	public void close() { pools.close(); }
}
