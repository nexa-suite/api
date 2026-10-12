package com.nexa.api.tenantaccessgovernance.support.application.model;

import java.time.Instant;
import java.util.UUID;

/** Exact support request facts safe for the owner and internal console views. */
public record SupportRequestView(UUID id, UUID tenantId, UUID workspaceId, String resourceType,
		UUID resourceId, UUID requestedByOperatorId, UUID approvedByOperatorId, String status,
		Instant expiresAt, Instant createdAt, long version) { }
