package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.List;
import java.util.UUID;

/** Read-only onboarding and Tenant binding health for an authenticated internal operator. */
public interface InternalConsoleOnboardingHealthQuery {
	List<InternalConsoleOnboardingHealth> listRecent(UUID internalOperatorId, int limit);
}
