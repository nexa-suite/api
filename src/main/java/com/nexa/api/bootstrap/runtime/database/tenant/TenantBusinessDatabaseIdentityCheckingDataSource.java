package com.nexa.api.bootstrap.runtime.database.tenant;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/** Refuses a connection unless its persisted Tenant and database identities match the registry. */
final class TenantBusinessDatabaseIdentityCheckingDataSource implements DataSource, AutoCloseable {
	private static final String IDENTITY_SQL = """
			SELECT tenant_id, database_identity
			FROM nexa_platform.tenant_business_database_identity
			WHERE singleton = TRUE
			""";

	private final DataSource delegate;
	private final TenantBusinessDatabaseBinding expected;

	TenantBusinessDatabaseIdentityCheckingDataSource(DataSource delegate, TenantBusinessDatabaseBinding expected) {
		this.delegate = Objects.requireNonNull(delegate, "Business database pool is required");
		this.expected = Objects.requireNonNull(expected, "Expected Tenant database binding is required");
	}

	@Override
	public Connection getConnection() throws SQLException {
		return verify(delegate.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		throw new SQLFeatureNotSupportedException(
				"Tenant database credentials come only from the provisioned secret reference");
	}

	private Connection verify(Connection connection) throws SQLException {
		try (var statement = connection.prepareStatement(IDENTITY_SQL);
				var result = statement.executeQuery()) {
			if (!result.next()) throw mismatch();
			UUID tenantId = result.getObject("tenant_id", UUID.class);
			UUID databaseIdentity = result.getObject("database_identity", UUID.class);
			if (result.next() || !expected.tenantId().value().equals(tenantId)
					|| !expected.databaseIdentity().equals(databaseIdentity)) {
				throw mismatch();
			}
			return closeOnClose(connection);
		} catch (SQLException exception) {
			try {
				connection.close();
			} catch (SQLException closeException) {
				exception.addSuppressed(closeException);
			}
			throw exception;
		}
	}

	private SQLNonTransientConnectionException mismatch() {
		return new SQLNonTransientConnectionException(
				"Physical Tenant database identity does not match the provisioned central binding", "28000");
	}

	private static Connection closeOnClose(Connection connection) {
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
				new Class<?>[]{Connection.class}, (proxy, method, args) -> {
					try {
						return method.invoke(connection, args);
					} catch (InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
	}

	@Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
	@Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
	@Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
	@Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
	@Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
	@Override public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this)) return iface.cast(this);
		throw new SQLException("Tenant database wrapper does not expose its delegate");
	}
	@Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
	@Override public void close() throws Exception {
		if (delegate instanceof AutoCloseable closeable) closeable.close();
	}
}
