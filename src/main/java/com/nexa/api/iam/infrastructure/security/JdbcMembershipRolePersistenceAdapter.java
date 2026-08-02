package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.port.out.MembershipRolePersistencePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.Set;
import java.util.UUID;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcMembershipRolePersistenceAdapter implements MembershipRolePersistencePort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcMembershipRolePersistenceAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public void assignFounderRoles(UUID membershipId, UUID tenantId, UUID workspaceId, Set<String> roles) { delegate.assignFounderRoles(membershipId, tenantId, workspaceId, roles); }
}
