package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.shared.context.RlsRequestScope;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/** Applies and clears the request tenant/workspace scope on every pooled connection. */
final class RlsScopedDataSource implements DataSource {
    private static final String SET_SCOPE_SQL = "select set_config('app.current_tenant_id', ?, ?), set_config('app.current_workspace_id', ?, ?), set_config('app.cross_scope_workspace_scan', ?, ?), set_config('app.access_context_user_id', '', ?)";
    private final DataSource delegate;

    RlsScopedDataSource(DataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "DataSource is required");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return scoped(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return scoped(delegate.getConnection(username, password));
    }

    private static Connection scoped(Connection connection) throws SQLException {
        try {
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) {
                // A pooled checkout must not inherit a transaction or session GUC
                // left behind by an earlier borrower.
                connection.rollback();
                connection.setAutoCommit(true);
            }
            applyScope(connection, null, false, true);
            if (!autoCommit) connection.setAutoCommit(false);
            applyScope(connection, RlsRequestScope.current(), !autoCommit, false);
        } catch (SQLException exception) {
            discard(connection, exception);
            throw exception;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("setAutoCommit") && args != null && args.length == 1 && args[0] instanceof Boolean autoCommit) {
                try {
                    Object result = invoke(connection, method, args);
                    if (!autoCommit) applyScope(connection, RlsRequestScope.current(), true, false);
                    else applyScope(connection, null, false, true);
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
                    applyScope(connection, null, false, true);
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
            applyScope(connection, null, false, true);
            return;
        }

        // set_config(..., false) issued inside a transaction is itself rolled
        // back with that transaction. Temporarily use autocommit so clearing
        // replaces the session value that would otherwise reappear after the
        // transaction-local scope ends.
        connection.setAutoCommit(true);
        try {
            applyScope(connection, null, false, true);
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

    private static void applyScope(Connection connection, RlsRequestScope.Scope scope, boolean local, boolean clearing) throws SQLException {
        try (var statement = connection.prepareStatement(SET_SCOPE_SQL)) {
            statement.setString(1, scope == null ? "" : scope.tenantId().toString());
            statement.setBoolean(2, local);
            statement.setString(3, scope == null ? "" : scope.workspaceId().toString());
            statement.setBoolean(4, local);
            statement.setString(5, !clearing && RlsRequestScope.crossScopeWorkspaceScanEnabled() ? "true" : "");
            statement.setBoolean(6, local);
            statement.setBoolean(7, local);
            statement.execute();
        }
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { if (iface.isInstance(this)) return iface.cast(this); return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this) || delegate.isWrapperFor(iface); }
}
