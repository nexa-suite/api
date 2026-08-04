package com.nexa.api.tenantmanagement.infrastructure.persistence;

import com.nexa.api.tenantmanagement.application.port.out.AuthorizationResolutionPort;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationResolutionRequest;
import com.nexa.api.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantmanagement.domain.model.membership.Membership;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Compatibility adapter used until the primary migration supplies dynamic
 * role-definition persistence. It still produces the canonical typed union.
 */
@Component
@Profile("test")
@ConditionalOnProperty(prefix = "nexa.tenant.roles", name = "persistence-enabled", havingValue = "false")
public final class FixedAuthorizationResolutionAdapter implements AuthorizationResolutionPort {
	@Override
	public EffectiveAuthorization resolve(AuthorizationResolutionRequest request) {
		return EffectiveAuthorization.fixed(request.fixedRoles(), request.authorizationVersion());
	}
}
