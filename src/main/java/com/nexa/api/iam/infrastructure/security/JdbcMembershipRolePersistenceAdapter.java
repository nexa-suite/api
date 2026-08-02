package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.port.out.MembershipRolePersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
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
        jdbc.update("delete from tenant_management.membership_role_assignment where membership_id=?", membershipId);
        for (String role : Set.copyOf(roles)) {
            jdbc.update("insert into tenant_management.membership_role_assignment (membership_id,tenant_id,workspace_id,role,assigned_at) values (?,?,?,?,?)",
                    membershipId, tenantId, workspaceId, role, Timestamp.from(now));
        }
    }
}
