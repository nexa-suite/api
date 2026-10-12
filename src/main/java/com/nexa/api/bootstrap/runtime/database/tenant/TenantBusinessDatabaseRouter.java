package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.stream.BaseStream;

/**
 * Explicit, opt-in route for one Tenant business transaction. Work runs synchronously on
 * the calling thread; the same-thread central-DataSource fence is not propagated to other
 * threads. This is not the API's global DataSource and has no shared-database fallback.
 */
public final class TenantBusinessDatabaseRouter implements AutoCloseable {
	private static final String CALLBACK_RESOURCE_ERROR =
			"Tenant work must not return JDBC resources or asynchronous results";
	private static final ThreadLocal<TenantBusinessDatabaseBinding> ACTIVE_ROUTE = new ThreadLocal<>();

	private final TenantBusinessDatabaseAuthority authority;
	private final TenantBusinessDatabasePoolRegistry pools;

	public TenantBusinessDatabaseRouter(TenantBusinessDatabaseAuthority authority,
			TenantBusinessDatabaseDataSourceFactory dataSourceFactory, int maxCachedPools) {
		this.authority = Objects.requireNonNull(authority, "Central Tenant database authority is required");
		this.pools = new TenantBusinessDatabasePoolRegistry(dataSourceFactory, maxCachedPools);
	}

	public <T> T inTransaction(CurrentAccessContext accessContext, TenantBusinessDatabaseWork<T> work) {
		Objects.requireNonNull(accessContext, "Verified Tenant access context is required");
		Objects.requireNonNull(work, "Tenant database work is required");
		if (ACTIVE_ROUTE.get() != null || TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
					"Tenant business routing cannot begin inside another database transaction");
		}

		TenantBusinessDatabaseBinding binding;
		try {
			binding = authority.requireReadyBinding(accessContext);
		} catch (TenantBusinessDatabaseUnavailableException exception) {
			pools.retireTenant(accessContext.tenantId());
			throw exception;
		}
		try (TenantBusinessDatabasePoolRegistry.Lease lease = pools.acquire(binding)) {
			DataSource tenantDataSource = RlsScopedDataSource.forVerifiedTenant(
					lease.dataSource(accessContext.workspaceId()), accessContext);
			TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(tenantDataSource));
			transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

			ACTIVE_ROUTE.set(binding);
			try {
				return transaction.execute(status -> {
					T result = work.execute(new JdbcTemplate(tenantDataSource));
					rejectEscapedResource(result);
					return result;
				});
			} finally {
				ACTIVE_ROUTE.remove();
			}
		}
	}

	private static void rejectEscapedResource(Object result) {
		if (result instanceof JdbcTemplate || result instanceof DataSource || result instanceof Connection
				|| result instanceof Statement || result instanceof ResultSet || result instanceof Future<?>
				|| result instanceof CompletionStage<?> || result instanceof BaseStream<?, ?>) {
			throw new IllegalStateException(CALLBACK_RESOURCE_ERROR);
		}
	}

	/** Rejects central checkout only on the routed transaction's current Java thread. */
	public static void assertCentralDataSourceAccessAllowed() throws SQLNonTransientConnectionException {
		if (ACTIVE_ROUTE.get() != null) {
			throw new SQLNonTransientConnectionException(
					"Central database access is not allowed inside a Tenant business transaction", "25000");
		}
	}

	@Override
	public void close() {
		pools.close();
	}
}
