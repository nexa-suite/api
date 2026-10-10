package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.shared.context.RlsRequestScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only, bounded central scope reader using its own least-privilege login. */
public final class JdbcTenantBusinessDocumentWorkerScopeQuery
		implements TenantBusinessDocumentWorkerScopeQuery, AutoCloseable {
	private static final String SCOPE_FROM = " FROM tenant_management.workspace w "
			+ "JOIN tenant_management.tenant_business_database_binding b ON b.tenant_id=w.tenant_id "
			+ "JOIN tenant_management.tenant_business_database_workspace_anchor_task a "
			+ "ON a.tenant_id=w.tenant_id AND a.workspace_id=w.id ";

	private final AutoCloseable pool;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;

	public JdbcTenantBusinessDocumentWorkerScopeQuery(DataSource pool) {
		DataSource requiredPool = Objects.requireNonNull(pool, "Dedicated central scope-reader pool is required");
		if (!(requiredPool instanceof AutoCloseable closeable)) {
			throw new IllegalArgumentException("Central scope-reader pool must be closeable");
		}
		this.pool = closeable;
		DataSource scoped = com.nexa.api.bootstrap.runtime.database.RlsScopedDataSource
				.forCurrentCentralScope(requiredPool);
		this.jdbc = new JdbcTemplate(scoped);
		this.transaction = new TransactionTemplate(new DataSourceTransactionManager(scoped));
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		this.transaction.setReadOnly(true);
	}

	@Override
	public List<Scope> listReadyWorkspaces(UUID afterTenantId, UUID afterWorkspaceId, int limit) {
		if ((afterTenantId == null) != (afterWorkspaceId == null)) {
			throw new IllegalArgumentException("Both pagination cursor identifiers must be supplied together");
		}
		if (limit < 1 || limit > 100) throw new IllegalArgumentException("Scope page size must be between 1 and 100");
		String cursor = afterTenantId == null ? "" : " AND (w.tenant_id,w.id) > (?::uuid,?::uuid)";
		List<Object> arguments = new ArrayList<>();
		if (afterTenantId != null) {
			arguments.add(afterTenantId);
			arguments.add(afterWorkspaceId);
		}
		arguments.add(limit);
		return withCrossScope(() -> transaction.execute(status -> {
			verifyScopeReaderRole();
			return jdbc.query("SELECT w.tenant_id,w.id " + SCOPE_FROM
					+ "WHERE w.status='ACTIVE' AND b.lifecycle_state='READY' AND a.status='READY'"
					+ cursor + " ORDER BY w.tenant_id,w.id LIMIT ?",
				(rs, row) -> new Scope(rs.getObject("tenant_id", UUID.class),
						rs.getObject("id", UUID.class)), arguments.toArray());
		}));
	}

	@Override
	public boolean isReadyWorkspace(UUID tenantId, UUID workspaceId) {
		Objects.requireNonNull(tenantId, "Tenant scope is required");
		Objects.requireNonNull(workspaceId, "Workspace scope is required");
		return withCrossScope(() -> Boolean.TRUE.equals(transaction.execute(status -> {
			verifyScopeReaderRole();
			return jdbc.queryForObject("SELECT EXISTS (SELECT 1 " + SCOPE_FROM
						+ "WHERE w.tenant_id=? AND w.id=? AND w.status='ACTIVE' "
						+ "AND b.lifecycle_state='READY' AND a.status='READY')",
					Boolean.class, tenantId, workspaceId);
		})));
	}

	private void verifyScopeReaderRole() {
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
		if (!"nexa_business_documents_scope_reader".equals(role) || !Boolean.TRUE.equals(safeRole)) {
			throw new IllegalStateException("Dedicated Business Documents scope-reader role is required");
		}
	}

	private <T> T withCrossScope(java.util.function.Supplier<T> query) {
		TenantBusinessDatabaseRouter.ensureRouteCanStart();
		RlsRequestScope.Scope previousScope = RlsRequestScope.current();
		boolean previousCrossScope = RlsRequestScope.crossScopeWorkspaceScanEnabled();
		try {
			RlsRequestScope.clear();
			RlsRequestScope.enableCrossScopeWorkspaceScan();
			return query.get();
		} finally {
			RlsRequestScope.clear();
			if (previousScope != null) RlsRequestScope.set(previousScope.tenantId(), previousScope.workspaceId());
			if (previousCrossScope) RlsRequestScope.enableCrossScopeWorkspaceScan();
		}
	}

	@Override
	public void close() {
		try {
			pool.close();
		} catch (Exception exception) {
			throw new IllegalStateException("Could not close the central business-document scope-reader pool", exception);
		}
	}
}
