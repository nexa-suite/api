package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.time.Instant;
import java.util.UUID;

/** Minimal internal health projection; registration fields are null for provisioning-only workspaces. */
public record InternalConsoleOnboardingHealth(UUID registrationId, String registrationStatus,
		UUID tenantId, UUID workspaceId, String provisioningStatus, Integer attemptCount,
		String registryLifecycleState, Instant updatedAt) { }
