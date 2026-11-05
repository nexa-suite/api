package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Central-database adapter. Its JdbcTemplate must use the central registry DataSource. */
public final class JdbcTenantBusinessDatabaseBindingRegistry implements TenantBusinessDatabaseBindingRegistry {
	private static final String FIND_READY_BINDING = """
			SELECT tenant_id, database_identity, credential_secret_reference, verified_schema_manifest_sha256
			FROM tenant_management.tenant_business_database_binding
			WHERE tenant_id = ? AND lifecycle_state = 'READY'
			""";
	private static final String HAS_ANY_BINDING = """
			SELECT EXISTS (
				SELECT 1
				FROM tenant_management.tenant_business_database_binding
				WHERE tenant_id = ?
			)
			""";
	private static final String LOCK_TENANT = """
			SELECT id
			FROM tenant_management.tenant
			WHERE id = ?
			FOR UPDATE
			""";
	private static final String ACTIVE_BUYER_MEMBERSHIP = """
			SELECT EXISTS (
				SELECT 1
				FROM tenant_management.workspace_membership membership
				JOIN tenant_management.tenant tenant
				  ON tenant.id = membership.tenant_id
				JOIN tenant_management.workspace workspace
				  ON workspace.tenant_id = membership.tenant_id
				 AND workspace.id = membership.workspace_id
				JOIN iam.user_account account
				  ON account.id = membership.user_id
				WHERE membership.id = ?
				  AND membership.tenant_id = ?
				  AND membership.workspace_id = ?
				  AND membership.membership_type = 'BUYER'
				  AND membership.status = 'ACTIVE'
				  AND tenant.status = 'ACTIVE'
				  AND workspace.status = 'ACTIVE'
				  AND account.status = 'ACTIVE'
			)
			""";

	private final JdbcTemplate centralRegistryJdbc;

	public JdbcTenantBusinessDatabaseBindingRegistry(JdbcTemplate centralRegistryJdbc) {
		this.centralRegistryJdbc = Objects.requireNonNull(centralRegistryJdbc,
				"Central registry JdbcTemplate is required");
	}

	@Override
	public Optional<TenantBusinessDatabaseBinding> findReadyBinding(TenantId tenantId) {
		Objects.requireNonNull(tenantId, "Tenant id is required");
		return centralRegistryJdbc.query(FIND_READY_BINDING,
				(result, row) -> new TenantBusinessDatabaseBinding(
						new TenantId(result.getObject("tenant_id", UUID.class)),
						result.getObject("database_identity", UUID.class),
						result.getString("credential_secret_reference"),
						result.getString("verified_schema_manifest_sha256")),
				tenantId.value()).stream().findFirst();
	}

	@Override
	public Optional<PaymentCallbackBinding> findReadyPaymentCallbackBinding(TenantId tenantId, WorkspaceId workspaceId) {
		Objects.requireNonNull(tenantId, "Tenant id is required");
		Objects.requireNonNull(workspaceId, "Workspace id is required");
		requireExactTenantWorkspaceScope(tenantId, workspaceId);
		String sql = "SELECT b.tenant_id,b.database_identity,b.payment_callback_credential_secret_reference "
				+ "AS credential_secret_reference,b.verified_schema_manifest_sha256,w.id AS workspace_id "
				+ "FROM tenant_management.tenant_business_database_binding b "
				+ "JOIN tenant_management.tenant t ON t.id=b.tenant_id "
				+ "JOIN tenant_management.workspace w ON w.tenant_id=b.tenant_id AND w.id=? "
				+ "JOIN tenant_management.tenant_business_database_workspace_anchor_task a "
				+ "ON a.tenant_id=w.tenant_id AND a.workspace_id=w.id "
				+ "WHERE b.tenant_id=? AND b.lifecycle_state='READY' AND t.status='ACTIVE' "
				+ "AND w.status='ACTIVE' AND a.status='READY' "
				+ "AND b.payment_callback_credential_secret_reference IS NOT NULL";
		return centralRegistryJdbc.query(sql, (result, row) -> new PaymentCallbackBinding(
				new TenantBusinessDatabaseBinding(new TenantId(result.getObject("tenant_id", UUID.class)),
						result.getObject("database_identity", UUID.class), result.getString("credential_secret_reference"),
						result.getString("verified_schema_manifest_sha256")),
				new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId(
						result.getObject("workspace_id", UUID.class))), workspaceId.value(), tenantId.value())
				.stream().findFirst();
	}

	@Override
	public boolean isActiveBuyerMembership(TenantId tenantId, WorkspaceId workspaceId, UUID membershipId) {
		Objects.requireNonNull(tenantId, "Tenant id is required");
		Objects.requireNonNull(workspaceId, "Workspace id is required");
		Objects.requireNonNull(membershipId, "Buyer membership id is required");
		requireExactTenantWorkspaceScope(tenantId, workspaceId);
		Boolean active = centralRegistryJdbc.queryForObject(ACTIVE_BUYER_MEMBERSHIP, Boolean.class,
				membershipId, tenantId.value(), workspaceId.value());
		return Boolean.TRUE.equals(active);
	}

	@Override
	public boolean hasAnyBinding(TenantId tenantId) {
		Objects.requireNonNull(tenantId, "Tenant id is required");
		requireExactTenantScope(tenantId);
		Boolean bound = centralRegistryJdbc.queryForObject(HAS_ANY_BINDING, Boolean.class, tenantId.value());
		return Boolean.TRUE.equals(bound);
	}

	@Override
	public boolean hasAnyBinding(UUID tenantId) {
		return hasAnyBinding(new TenantId(Objects.requireNonNull(tenantId, "Tenant scope is required")));
	}

	@Override
	public boolean lockAndCheckNoBinding(TenantId tenantId) {
		Objects.requireNonNull(tenantId, "Tenant id is required");
		requireExactTenantScope(tenantId);
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Tenant binding lock requires a central registry transaction");
		}
		boolean tenantExists = !centralRegistryJdbc.query(LOCK_TENANT,
				(result, row) -> result.getObject("id", UUID.class), tenantId.value()).isEmpty();
		return tenantExists && !hasAnyBinding(tenantId);
	}

	@Override
	public boolean lockAndCheckNoBinding(UUID tenantId) {
		return lockAndCheckNoBinding(new TenantId(Objects.requireNonNull(tenantId, "Tenant scope is required")));
	}

	private static void requireExactTenantScope(TenantId tenantId) {
		RlsRequestScope.Scope scope = RlsRequestScope.current();
		if (scope == null || scope.tenantId() == null || scope.workspaceId() == null
				|| !scope.tenantId().equals(tenantId.value())
				|| RlsRequestScope.crossScopeWorkspaceScanEnabled()) {
			throw new IllegalStateException("Tenant binding checks require the exact Tenant/Workspace RLS scope");
		}
	}

	private static void requireExactTenantWorkspaceScope(TenantId tenantId, WorkspaceId workspaceId) {
		RlsRequestScope.Scope scope = RlsRequestScope.current();
		if (scope == null || !tenantId.value().equals(scope.tenantId())
				|| !workspaceId.value().equals(scope.workspaceId())
				|| RlsRequestScope.crossScopeWorkspaceScanEnabled()) {
			throw new IllegalStateException("Payment callback binding requires the exact Tenant/Workspace scope");
		}
	}
}
