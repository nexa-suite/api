package com.nexa.api.tenantmanagement.application.port.out;

import com.nexa.api.tenantmanagement.domain.model.access.EffectiveAuthorization;

/**
 * Resolves the current role-definition union for a membership. Persistence
 * owns the implementation; the domain never queries role-assignment tables.
 */
public interface AuthorizationResolutionPort {
	EffectiveAuthorization resolve(AuthorizationResolutionRequest request);
}
