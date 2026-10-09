package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;

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
	private static final String SCOPE_ANCHOR_SQL = """
			SELECT 1
			FROM nexa_platform.tenant_workspace_scope_anchor
			WHERE tenant_id = ? AND workspace_id = ?
			""";

	private final DataSource delegate;
	private final TenantBusinessDatabaseBinding expected;

	TenantBusinessDatabaseIdentityCheckingDataSource(DataSource delegate, TenantBusinessDatabaseBinding expected) {
		this.delegate = Objects.requireNonNull(delegate, "Business database pool is required");
		this.expected = Objects.requireNonNull(expected, "Expected Tenant database binding is required");
	}

	DataSource forVerifiedWorkspace(WorkspaceId workspaceId) {
		return new WorkspaceCheckingDataSource(this, expected.tenantId().value(),
				Objects.requireNonNull(workspaceId, "Verified Workspace id is required").value());
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

	private static SQLNonTransientConnectionException scopeMismatch() {
		return new SQLNonTransientConnectionException(
				"Physical Tenant database scope does not match the verified Tenant/Workspace access context", "28000");
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

	private static final class WorkspaceCheckingDataSource implements DataSource {
		private final DataSource identityChecked;
		private final UUID expectedTenantId;
		private final UUID expectedWorkspaceId;

		private WorkspaceCheckingDataSource(DataSource identityChecked, UUID expectedTenantId, UUID expectedWorkspaceId) {
			this.identityChecked = identityChecked;
			this.expectedTenantId = expectedTenantId;
			this.expectedWorkspaceId = expectedWorkspaceId;
		}

		@Override
		public Connection getConnection() throws SQLException {
			Connection connection = identityChecked.getConnection();
			try (var statement = connection.prepareStatement(SCOPE_ANCHOR_SQL)) {
				statement.setObject(1, expectedTenantId);
				statement.setObject(2, expectedWorkspaceId);
				try (var result = statement.executeQuery()) {
					if (!result.next() || result.next()) throw scopeMismatch();
					return connection;
				}
			} catch (SQLException exception) {
				try {
					connection.close();
				} catch (SQLException closeException) {
					exception.addSuppressed(closeException);
				}
				throw exception;
			}
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			throw new SQLFeatureNotSupportedException(
					"Tenant database credentials come only from the provisioned secret reference");
		}

		@Override public PrintWriter getLogWriter() throws SQLException { return identityChecked.getLogWriter(); }
		@Override public void setLogWriter(PrintWriter out) throws SQLException { identityChecked.setLogWriter(out); }
		@Override public void setLoginTimeout(int seconds) throws SQLException { identityChecked.setLoginTimeout(seconds); }
		@Override public int getLoginTimeout() throws SQLException { return identityChecked.getLoginTimeout(); }
		@Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return identityChecked.getParentLogger(); }
		@Override public <T> T unwrap(Class<T> iface) throws SQLException {
			if (iface.isInstance(this)) return iface.cast(this);
			throw new SQLException("Tenant database scope wrapper does not expose its delegate");
		}
		@Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
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
