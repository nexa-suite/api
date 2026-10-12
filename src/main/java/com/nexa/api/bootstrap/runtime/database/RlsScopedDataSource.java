package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Applies and clears either request-local central scope or explicit verified Tenant business scope. */
public final class RlsScopedDataSource implements DataSource {
    private static final String SET_SCOPE_SQL = "select set_config('app.current_tenant_id', ?, ?), set_config('app.current_workspace_id', ?, ?), set_config('app.cross_scope_workspace_scan', ?, ?), set_config('app.access_context_user_id', '', ?), set_config('app.expected_purchase_request_expiry_policy_revision', '', ?)";
    private final DataSource delegate;
    private final Supplier<RlsRequestScope.Scope> scopeProvider;
    private final BooleanSupplier crossScopeScanProvider;
    private final boolean centralDataSource;

    RlsScopedDataSource(DataSource delegate) {
        this(delegate, RlsRequestScope::current, RlsRequestScope::crossScopeWorkspaceScanEnabled, true);
    }

    private RlsScopedDataSource(DataSource delegate, Supplier<RlsRequestScope.Scope> scopeProvider,
            BooleanSupplier crossScopeScanProvider, boolean centralDataSource) {
        this.delegate = Objects.requireNonNull(delegate, "DataSource is required");
        this.scopeProvider = Objects.requireNonNull(scopeProvider, "RLS scope provider is required");
        this.crossScopeScanProvider = Objects.requireNonNull(crossScopeScanProvider,
                "RLS cross-scope provider is required");
        this.centralDataSource = centralDataSource;
    }

    /** Creates a business DataSource scoped to the access context already revalidated by central authority. */
    public static DataSource forVerifiedTenant(DataSource delegate, CurrentAccessContext accessContext) {
        Objects.requireNonNull(accessContext, "Revalidated Tenant access context is required");
        RlsRequestScope.Scope verifiedScope = new RlsRequestScope.Scope(
                accessContext.tenantId().value(), accessContext.workspaceId().value());
        return new RlsScopedDataSource(delegate, () -> verifiedScope, () -> false, false);
    }

    /** Applies an explicit trusted system-worker scope without inventing a human access context. */
    public static DataSource forVerifiedSystemTenant(DataSource delegate, TenantId tenantId,
            WorkspaceId workspaceId) {
        Objects.requireNonNull(tenantId, "Verified system Tenant id is required");
        Objects.requireNonNull(workspaceId, "Verified system Workspace id is required");
        RlsRequestScope.Scope verifiedScope = new RlsRequestScope.Scope(tenantId.value(), workspaceId.value());
        return new RlsScopedDataSource(delegate, () -> verifiedScope, () -> false, false);
    }

    /** Wraps an isolated central worker pool with the same request-local RLS context as the primary pool. */
    public static DataSource forCurrentCentralScope(DataSource delegate) {
        return new RlsScopedDataSource(delegate, RlsRequestScope::current,
                RlsRequestScope::crossScopeWorkspaceScanEnabled, true);
    }

    @Override
    public Connection getConnection() throws SQLException {
        assertAccessAllowed();
        return scoped(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        assertAccessAllowed();
        return scoped(delegate.getConnection(username, password));
    }

    private void assertAccessAllowed() throws SQLException {
        if (centralDataSource) TenantBusinessDatabaseRouter.assertCentralDataSourceAccessAllowed();
    }

    private Connection scoped(Connection connection) throws SQLException {
        try {
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) {
                // A pooled checkout must not inherit a transaction or session GUC
                // left behind by an earlier borrower.
                connection.rollback();
                connection.setAutoCommit(true);
            }
            applyScope(connection, null, false, false);
            if (!autoCommit) connection.setAutoCommit(false);
            applyScope(connection, scopeProvider.get(), !autoCommit, crossScopeScanProvider.getAsBoolean());
        } catch (SQLException exception) {
            discard(connection, exception);
            throw exception;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("setAutoCommit") && args != null && args.length == 1 && args[0] instanceof Boolean autoCommit) {
                try {
                    Object result = invoke(connection, method, args);
                    if (!autoCommit) applyScope(connection, scopeProvider.get(), true,
                            crossScopeScanProvider.getAsBoolean());
                    else applyScope(connection, null, false, false);
                    return result;
                } catch (Throwable exception) {
                    discard(connection, exception);
                    throw exception;
                }
            }
            if ((method.getName().equals("commit") || method.getName().equals("rollback"))
                    && method.getParameterCount() == 0) {
                try {
                    Object result = invoke(connection, method, args);
                    clearAfterTransaction(connection);
                    return result;
                } catch (Throwable exception) {
                    // The transaction outcome or scope cleanup is uncertain. Do
                    // not return a connection with a possibly live scope to the pool.
                    discard(connection, exception);
                    throw exception;
                }
            }
            if (method.getName().equals("close") && method.getParameterCount() == 0) {
                if (connection.isClosed()) {
                    connection.close();
                    return null;
                }
                Throwable failure = null;
                try {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                        connection.setAutoCommit(true);
                    }
                    applyScope(connection, null, false, false);
                } catch (Throwable exception) {
                    failure = exception;
                    discard(connection, exception);
                }
                try {
                    connection.close();
                } catch (Throwable closeException) {
                    if (failure == null) failure = closeException;
                    else failure.addSuppressed(closeException);
                }
                if (failure != null) throw failure;
                return null;
            }
            return invoke(connection, method, args);
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    private static Object invoke(Connection connection, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(connection, args);
        } catch (java.lang.reflect.InvocationTargetException invocation) {
            throw invocation.getCause();
        }
    }

    private static void clearAfterTransaction(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        if (autoCommit) {
            applyScope(connection, null, false, false);
            return;
        }

        // set_config(..., false) issued inside a transaction is itself rolled
        // back with that transaction. Temporarily use autocommit so clearing
        // replaces the session value that would otherwise reappear after the
        // transaction-local scope ends.
        connection.setAutoCommit(true);
        try {
            applyScope(connection, null, false, false);
        } finally {
            connection.setAutoCommit(false);
        }
    }

    private static void discard(Connection connection, Throwable failure) {
        try {
            connection.abort(Runnable::run);
        } catch (Throwable abortException) {
            failure.addSuppressed(abortException);
            try { connection.close(); } catch (Throwable closeException) { failure.addSuppressed(closeException); }
        }
    }

    private static void applyScope(Connection connection, RlsRequestScope.Scope scope, boolean local,
            boolean crossScopeWorkspaceScan) throws SQLException {
        try (var statement = connection.prepareStatement(SET_SCOPE_SQL)) {
            statement.setString(1, scope == null ? "" : scope.tenantId().toString());
            statement.setBoolean(2, local);
            statement.setString(3, scope == null ? "" : scope.workspaceId().toString());
            statement.setBoolean(4, local);
            statement.setString(5, crossScopeWorkspaceScan ? "true" : "");
            statement.setBoolean(6, local);
            statement.setBoolean(7, local);
            statement.setBoolean(8, local);
            statement.execute();
        }
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        assertAccessAllowed();
        return delegate.unwrap(iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this) || delegate.isWrapperFor(iface); }
}
