package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.application.port.out.CatalogClientAccountPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCatalogClientAccountAdapter implements CatalogClientAccountPort {
	private final JdbcTemplate jdbc;

	public JdbcCatalogClientAccountAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
		return jdbc.query("select client_account_id from sales.client_account_membership where tenant_id=? and workspace_id=? and workspace_membership_id=?",
				(rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, membershipId).stream().findFirst();
	}

	@Override
	public Optional<ClientAccountProfile> findProfileForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
		return jdbc.query("select a.id,a.segment from sales.client_account a join sales.client_account_membership m on m.client_account_id=a.id and m.tenant_id=a.tenant_id and m.workspace_id=a.workspace_id where a.tenant_id=? and a.workspace_id=? and m.workspace_membership_id=? and a.status='ACTIVE'",
				(rs, row) -> new ClientAccountProfile(rs.getObject(1, UUID.class), rs.getString(2), null), tenantId, workspaceId, membershipId).stream().findFirst();
	}
}
