package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.AuthorizationVersionPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Invalidates effective authorization snapshots for every membership in a workspace. */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant.roles", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAuthorizationVersionAdapter implements AuthorizationVersionPort {
	private final JdbcTemplate jdbc;

	public JdbcAuthorizationVersionAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	@Transactional
	public void bump(TenantId tenantId, WorkspaceId workspaceId) {
		jdbc.update("insert into tenant_management.membership_authorization_state (membership_id,tenant_id,workspace_id,authorization_version,updated_at) "
				+ "select m.id,w.tenant_id,m.workspace_id,1,current_timestamp from tenant_management.workspace_membership m "
				+ "join tenant_management.workspace w on w.id=m.workspace_id where w.tenant_id=? and w.id=? "
				+ "on conflict (membership_id) do update set authorization_version=tenant_management.membership_authorization_state.authorization_version+1,updated_at=current_timestamp",
				tenantId.value(), workspaceId.value());
	}
}
