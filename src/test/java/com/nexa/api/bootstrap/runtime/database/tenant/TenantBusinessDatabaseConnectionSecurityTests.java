package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLNonTransientConnectionException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantBusinessDatabaseConnectionSecurityTests {
	@ParameterizedTest
	@ValueSource(strings = { "missing", "duplicate", "wrong-tenant", "wrong-database", "null-tenant", "null-database" })
	void rejectsMissingOrMismatchedPhysicalIdentityAndClosesTheConnection(String failure) throws Exception {
		TenantId tenant = TenantId.random();
		UUID identity = UUID.randomUUID();
		DataSource delegate = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		ResultSet result = mock(ResultSet.class);
		when(delegate.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(result);
		when(result.next()).thenReturn(!failure.equals("missing"), failure.equals("duplicate"));
		when(result.getObject("tenant_id", UUID.class)).thenReturn(failure.equals("null-tenant") ? null
				: failure.equals("wrong-tenant") ? UUID.randomUUID() : tenant.value());
		when(result.getObject("database_identity", UUID.class)).thenReturn(failure.equals("null-database") ? null
				: failure.equals("wrong-database") ? UUID.randomUUID() : identity);
		var guarded = new TenantBusinessDatabaseIdentityCheckingDataSource(delegate,
				new TenantBusinessDatabaseBinding(tenant, identity, "opaque-reference"));

		assertThatThrownBy(guarded::getConnection).isInstanceOf(SQLNonTransientConnectionException.class)
				.hasMessageContaining("identity does not match");
		verify(connection).close();
	}

	@ParameterizedTest
	@ValueSource(strings = { "missing", "mismatched" })
	void refusesUnattestedSchemaManifestBeforeOpeningAnyPool(String failure) {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		TenantId tenant = TenantId.random();
		when(context.tenantId()).thenReturn(tenant);
		TenantBusinessDatabaseAuthority authority = mock(TenantBusinessDatabaseAuthority.class);
		TenantBusinessDatabaseDataSourceFactory pools = mock(TenantBusinessDatabaseDataSourceFactory.class);
		when(authority.requireReadyBinding(context)).thenReturn(new TenantBusinessDatabaseBinding(tenant,
				UUID.randomUUID(), "opaque-reference", failure.equals("missing") ? null : "b".repeat(64)));
		try (var router = new TenantBusinessDatabaseRouter(authority, pools, 1, "a".repeat(64))) {
			assertThatThrownBy(() -> router.inTransaction(context, jdbc -> "must-not-run"))
					.isInstanceOf(TenantBusinessDatabaseSchemaManifestMismatchException.class);
			verifyNoInteractions(pools);
		}
	}
}
