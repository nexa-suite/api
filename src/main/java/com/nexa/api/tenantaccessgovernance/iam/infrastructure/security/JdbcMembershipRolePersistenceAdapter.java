package com.nexa.api.tenantaccessgovernance.iam.infrastructure.security;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.MembershipRolePersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcMembershipRolePersistenceAdapter implements MembershipRolePersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcMembershipRolePersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void assignFounderRoles(UUID membershipId, UUID tenantId, UUID workspaceId, Set<String> roles) {
        Objects.requireNonNull(roles, "Founder roles are required");
        if (roles.isEmpty()) throw new IllegalArgumentException("Founder roles cannot be empty");
        Instant now = Instant.now();
        jdbc.update("delete from tenant_management.membership_role_definition where membership_id=?", membershipId);
        for (String role : Set.copyOf(roles)) {
			jdbc.update("insert into tenant_management.membership_role_definition "
					+ "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
					+ "select ?,?,?,r.id,? from tenant_management.role_definition r "
					+ "where r.tenant_id is null and r.code=lower(?) and r.status='ACTIVE' "
					+ "on conflict (membership_id,role_id) do nothing",
					membershipId, tenantId, workspaceId, java.sql.Timestamp.from(now), role);
        }
    }
}
