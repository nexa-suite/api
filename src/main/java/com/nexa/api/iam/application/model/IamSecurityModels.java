package com.nexa.api.iam.application.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Stable application DTOs shared by narrow inbound ports and outbound persistence ports. */
public final class IamSecurityModels {
    private IamSecurityModels() {}

    public record Actor(UUID userId, UUID sessionId, String surface, UUID tenantId, UUID workspaceId,
            String correlationId, String traceId) {}

    public record Profile(UUID userId, String email, String displayName, String phone, String preferredLanguage,
            String timezone, long version) {}

    public record ProfilePatch(String displayName, String phone, String preferredLanguage, String timezone, long version) {}

    public record Session(UUID id, String surface, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
            boolean current, String deviceLabel, String coarseIp) {}

    public record Registration(String id, String status, Instant submittedAt, String statusToken) {
        public Registration(String id, String status, Instant submittedAt) {
            this(id, status, submittedAt, null);
        }
    }

    public record RegistrationRequest(String legalName, String displayName, String businessIdentifier,
            String operationCategory, String storageSiteName, String storageSiteAddress, String founderEmail,
            String founderDisplayName, String workspaceName, String workspaceSlug, String referencePlan,
            String termsVersion, boolean termsAccepted) {}

    public record Activation(String registrationId, String status, UUID tenantId, UUID workspaceId, UUID founderUserId,
            Set<String> roles) {}
}
