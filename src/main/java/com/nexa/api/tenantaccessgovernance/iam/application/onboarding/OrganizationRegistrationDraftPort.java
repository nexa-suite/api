package com.nexa.api.tenantaccessgovernance.iam.application.onboarding;

import java.util.Map;
import java.util.UUID;

/** Application boundary for public, token-scoped registration drafts. */
public interface OrganizationRegistrationDraftPort {
    OrganizationRegistrationDraftModels.Created create();
    OrganizationRegistrationDraftModels.Draft get(UUID registrationId, String resumeToken);
    OrganizationRegistrationDraftModels.Draft updateStep(UUID registrationId, String resumeToken,
            int expectedVersion, int step, Map<String, Object> values, String idempotencyKey);
    OrganizationRegistrationDraftModels.Draft submit(UUID registrationId, String resumeToken,
            int expectedVersion, String idempotencyKey);
}
