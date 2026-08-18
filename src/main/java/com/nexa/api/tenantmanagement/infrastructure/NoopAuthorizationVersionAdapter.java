package com.nexa.api.tenantmanagement.infrastructure;

import com.nexa.api.tenantmanagement.application.port.out.AuthorizationVersionPort;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Primary replaces this with the membership/session invalidation adapter. */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant.roles", name = "version-adapter-enabled", havingValue = "false")
public final class NoopAuthorizationVersionAdapter implements AuthorizationVersionPort {
	@Override public void bump(TenantId tenantId, WorkspaceId workspaceId) { }
}
