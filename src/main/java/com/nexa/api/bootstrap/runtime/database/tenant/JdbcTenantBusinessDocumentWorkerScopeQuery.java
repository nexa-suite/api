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
	private final UUID boundTenantId;

	public JdbcTenantBusinessDocumentWorkerScopeQuery(DataSource pool) {
		this(pool, null);
	}

	/** Restricts a managed cloud worker to its configured Tenant before enumerating READY metadata. */
	public static JdbcTenantBusinessDocumentWorkerScopeQuery forCloudBinding(DataSource pool, UUID tenantId) {
		return new JdbcTenantBusinessDocumentWorkerScopeQuery(pool, Objects.requireNonNull(tenantId, "Cloud Tenant is required"));
	}

	private JdbcTenantBusinessDocumentWorkerScopeQuery(DataSource pool, UUID boundTenantId) {
		this.boundTenantId = boundTenantId;
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
		if (boundTenantId != null && afterTenantId != null && !boundTenantId.equals(afterTenantId)) {
			throw new IllegalArgumentException("Scope cursor escaped the configured Tenant binding");
		}
		String tenantFilter = boundTenantId == null ? "" : " AND w.tenant_id=?";
		String cursor = afterTenantId == null ? "" : " AND (w.tenant_id,w.id) > (?::uuid,?::uuid)";
		List<Object> arguments = new ArrayList<>();
		if (boundTenantId != null) arguments.add(boundTenantId);
		if (afterTenantId != null) {
			arguments.add(afterTenantId);
			arguments.add(afterWorkspaceId);
		}
		arguments.add(limit);
		return withCrossScope(() -> transaction.execute(status -> {
			verifyScopeReaderRole();
			return jdbc.query("SELECT w.tenant_id,w.id " + SCOPE_FROM
					+ "WHERE w.status='ACTIVE' AND b.lifecycle_state='READY' AND a.status='READY'"
					+ tenantFilter + cursor + " ORDER BY w.tenant_id,w.id LIMIT ?",
				(rs, row) -> new Scope(rs.getObject("tenant_id", UUID.class),
						rs.getObject("id", UUID.class)), arguments.toArray());
		}));
	}

	@Override
	public boolean isReadyWorkspace(UUID tenantId, UUID workspaceId) {
		Objects.requireNonNull(tenantId, "Tenant scope is required");
		Objects.requireNonNull(workspaceId, "Workspace scope is required");
		if (boundTenantId != null && !boundTenantId.equals(tenantId)) return false;
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
		Boolean safeRole = jdbc.queryForObject("""
				SELECT reader.rolcanlogin AND NOT reader.rolsuper AND NOT reader.rolcreatedb
				    AND NOT reader.rolcreaterole AND NOT reader.rolreplication AND NOT reader.rolbypassrls
				    AND NOT EXISTS (
				        SELECT 1 FROM pg_auth_members membership
				        WHERE (membership.member=reader.oid OR membership.roleid=reader.oid)
				          AND (CAST(? AS boolean) AND NOT reader.rolinherit AND membership.roleid=reader.oid
				               AND membership.member=(SELECT datdba FROM pg_database WHERE datname=current_database())
				               AND pg_get_userbyid(membership.member)='neondb_owner'
				               AND pg_get_userbyid(membership.grantor)='cloud_admin'
				               AND membership.admin_option AND NOT membership.inherit_option AND NOT membership.set_option) IS NOT TRUE
				    )
				    AND NOT EXISTS (SELECT 1 FROM pg_database WHERE datdba=reader.oid)
				    AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=reader.oid)
				    AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relowner=reader.oid)
				    AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE proowner=reader.oid)
				    AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege(reader.oid, oid, 'CREATE'))
				FROM pg_roles reader WHERE reader.rolname=current_user
				""", Boolean.class, boundTenantId != null);
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
