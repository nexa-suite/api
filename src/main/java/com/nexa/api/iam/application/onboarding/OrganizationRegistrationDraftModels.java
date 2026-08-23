package com.nexa.api.iam.application.onboarding;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Publicly safe views for the resumable six-step organization onboarding flow. */
public final class OrganizationRegistrationDraftModels {
    private OrganizationRegistrationDraftModels() { }

    public record Draft(UUID registrationId, String status, int lastCompletedStep,
            Set<Integer> completedSteps, Map<String, Object> data, long version,
            Instant createdAt, Instant updatedAt) { }

    public record Created(Draft draft, String resumeToken) { }
}
