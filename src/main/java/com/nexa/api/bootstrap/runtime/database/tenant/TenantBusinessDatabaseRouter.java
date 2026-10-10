package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionException;
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
	private final String requiredSchemaManifestSha256;

	public TenantBusinessDatabaseRouter(TenantBusinessDatabaseAuthority authority,
			TenantBusinessDatabaseDataSourceFactory dataSourceFactory, int maxCachedPools) {
		this(authority, dataSourceFactory, maxCachedPools, null);
	}

	public TenantBusinessDatabaseRouter(TenantBusinessDatabaseAuthority authority,
			TenantBusinessDatabaseDataSourceFactory dataSourceFactory, int maxCachedPools,
			String requiredSchemaManifestSha256) {
		this.authority = Objects.requireNonNull(authority, "Central Tenant database authority is required");
		this.pools = new TenantBusinessDatabasePoolRegistry(dataSourceFactory, maxCachedPools);
		if (requiredSchemaManifestSha256 != null && !requiredSchemaManifestSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Required Tenant schema manifest digest is invalid");
		}
		this.requiredSchemaManifestSha256 = requiredSchemaManifestSha256;
	}

	public <T> T inTransaction(CurrentAccessContext accessContext, TenantBusinessDatabaseWork<T> work) {
		Objects.requireNonNull(accessContext, "Verified Tenant access context is required");
		Objects.requireNonNull(work, "Tenant database work is required");
		return inTenantSession(accessContext, session -> session.inTransaction(work));
	}

	/**
	 * Holds one verified Tenant route lease across sequential Tenant transactions. Provider calls may run
	 * between commits, while each checked-out connection is returned before the next phase begins.
	 */
	public <T> T inTenantSession(CurrentAccessContext accessContext,
			TenantBusinessDatabaseSessionWork<T> work) {
		Objects.requireNonNull(accessContext, "Verified Tenant access context is required");
		Objects.requireNonNull(work, "Tenant database session work is required");
		ensureRouteCanStart();

		TenantBusinessDatabaseBinding binding;
		try {
			binding = authority.requireReadyBinding(accessContext);
		} catch (TenantBusinessDatabaseUnavailableException exception) {
			pools.retireTenant(accessContext.tenantId());
			throw exception;
		}
		if (requiredSchemaManifestSha256 != null
				&& !requiredSchemaManifestSha256.equals(binding.verifiedSchemaManifestSha256())) {
			pools.retireTenant(accessContext.tenantId());
			throw new TenantBusinessDatabaseSchemaManifestMismatchException();
		}
		try (TenantBusinessDatabasePoolRegistry.Lease lease = pools.acquire(binding)) {
			DataSource tenantDataSource = RlsScopedDataSource.forVerifiedTenant(
					lease.dataSource(accessContext.workspaceId()), accessContext);
		DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(tenantDataSource);

			enterRoutedTransaction(binding);
			try {
				T result = work.execute(new TenantBusinessDatabaseSession(new JdbcTemplate(tenantDataSource),
						transactionManager, authority, accessContext, binding, requiredSchemaManifestSha256));
				rejectEscapedResource(result);
				return result;
			} finally {
				exitRoutedTransaction();
			}
		}
	}

	static void ensureRouteCanStart() {
		if (ACTIVE_ROUTE.get() != null || TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
					"Tenant business routing cannot begin inside another database transaction");
		}
	}

	static void enterRoutedTransaction(TenantBusinessDatabaseBinding binding) {
		if (ACTIVE_ROUTE.get() != null) {
			throw new IllegalStateException("Nested Tenant database routing is not allowed");
		}
		ACTIVE_ROUTE.set(Objects.requireNonNull(binding));
	}

	static void exitRoutedTransaction() {
		ACTIVE_ROUTE.remove();
	}

	private static void rejectEscapedResource(Object result) {
		if (result instanceof TenantBusinessDatabaseSession || result instanceof JdbcTemplate
				|| result instanceof PlatformTransactionManager || result instanceof DataSource || result instanceof Connection
				|| result instanceof Statement || result instanceof ResultSet || result instanceof Future<?>
				|| result instanceof CompletionStage<?> || result instanceof BaseStream<?, ?>) {
			throw new IllegalStateException(CALLBACK_RESOURCE_ERROR);
		}
	}

	@FunctionalInterface
	public interface TenantBusinessDatabaseSessionWork<T> {
		T execute(TenantBusinessDatabaseSession session);
	}

	/** Short-lived JDBC/transaction bindings; never retain beyond the router callback. */
	public static final class TenantBusinessDatabaseSession {
		private final JdbcTemplate jdbc;
		private final PlatformTransactionManager transactionManager;

		private TenantBusinessDatabaseSession(JdbcTemplate jdbc, DataSourceTransactionManager delegate,
				TenantBusinessDatabaseAuthority authority, CurrentAccessContext accessContext,
				TenantBusinessDatabaseBinding binding, String requiredSchemaManifestSha256) {
			this.jdbc = jdbc;
			this.transactionManager = new GuardedTenantTransactionManager(delegate, authority, accessContext,
					binding, requiredSchemaManifestSha256);
		}

		public JdbcTemplate jdbcTemplate() { return jdbc; }
		public PlatformTransactionManager transactionManager() { return transactionManager; }

		public <T> T inTransaction(TenantBusinessDatabaseWork<T> work) {
			Objects.requireNonNull(work, "Tenant database work is required");
			TransactionTemplate transaction = new TransactionTemplate(transactionManager);
			transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
			return transaction.execute(status -> {
				T result = work.execute(jdbc);
				rejectEscapedResource(result);
				return result;
			});
		}
	}

	private static final class GuardedTenantTransactionManager implements PlatformTransactionManager {
		private final PlatformTransactionManager delegate;
		private final TenantBusinessDatabaseAuthority authority;
		private final CurrentAccessContext accessContext;
		private final TenantBusinessDatabaseBinding binding;
		private final String requiredSchemaManifestSha256;

		private GuardedTenantTransactionManager(PlatformTransactionManager delegate,
				TenantBusinessDatabaseAuthority authority, CurrentAccessContext accessContext,
				TenantBusinessDatabaseBinding binding, String requiredSchemaManifestSha256) {
			this.delegate = delegate;
			this.authority = authority;
			this.accessContext = accessContext;
			this.binding = binding;
			this.requiredSchemaManifestSha256 = requiredSchemaManifestSha256;
		}

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
			if (!TransactionSynchronizationManager.isActualTransactionActive()) verifyBinding();
			return delegate.getTransaction(definition);
		}

		@Override
		public void commit(TransactionStatus status) throws TransactionException {
			boolean transactionOwner = status.isNewTransaction();
			delegate.commit(status);
			if (transactionOwner) verifyBinding();
		}

		@Override
		public void rollback(TransactionStatus status) throws TransactionException {
			boolean transactionOwner = status.isNewTransaction();
			delegate.rollback(status);
			if (transactionOwner) verifyBinding();
		}

		private void verifyBinding() {
			exitRoutedTransaction();
			try {
				TenantBusinessDatabaseBinding current = authority.requireReadyBinding(accessContext);
				if (!binding.equals(current) || (requiredSchemaManifestSha256 != null
						&& !requiredSchemaManifestSha256.equals(current.verifiedSchemaManifestSha256()))) {
					throw new TenantBusinessDatabaseSchemaManifestMismatchException();
				}
			} finally {
				enterRoutedTransaction(binding);
			}
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
