package com.nexa.api.shared.infrastructure.security;

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
    private static final String SET_SCOPE_SQL = "select set_config('app.current_tenant_id', ?, false), set_config('app.current_workspace_id', ?, false)";
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
        var scope = RlsRequestScope.current();
        try (var statement = connection.prepareStatement(SET_SCOPE_SQL)) {
            statement.setString(1, scope == null ? "" : scope.tenantId().toString());
            statement.setString(2, scope == null ? "" : scope.workspaceId().toString());
            statement.execute();
        } catch (SQLException exception) {
            try { connection.close(); } catch (SQLException closeException) { exception.addSuppressed(closeException); }
            throw exception;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("close") && method.getParameterCount() == 0) {
                try (var statement = connection.prepareStatement(SET_SCOPE_SQL)) {
                    statement.setString(1, "");
                    statement.setString(2, "");
                    statement.execute();
                } finally {
                    connection.close();
                }
                return null;
            }
            try {
                return method.invoke(connection, args);
            } catch (java.lang.reflect.InvocationTargetException invocation) {
                throw invocation.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { if (iface.isInstance(this)) return iface.cast(this); return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this) || delegate.isWrapperFor(iface); }
}
