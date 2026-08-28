package com.nexa.api.notifications.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BC-10 persistence boundary for provider-neutral native push routing. */
public interface PushSubscriptionPersistencePort {
    PushSubscription register(RegisterRequest request);
    PushSubscription disable(DisableRequest request);
    List<PushSubscription> activeForRecipient(UUID tenantId, UUID workspaceId, UUID recipientMembershipId);
    void recordAttempt(DeliveryAttempt request);

    record RegisterRequest(UUID tenantId, UUID workspaceId, UUID recipientMembershipId, UUID userId,
                           String surface, String installationId, String platform, String tokenHash,
                           UUID actorMembershipId, String idempotencyKey, String requestHash, Instant now) { }

    record DisableRequest(UUID tenantId, UUID workspaceId, UUID recipientMembershipId, UUID subscriptionId,
                          String operation, UUID actorMembershipId, String idempotencyKey,
                          String requestHash, Instant now) { }

    record PushSubscription(UUID id, UUID recipientMembershipId, String installationId, String platform,
                            String surface, String status, Instant createdAt, Instant updatedAt, long version) { }

    record DeliveryAttempt(UUID tenantId, UUID workspaceId, UUID subscriptionId, String eventId,
                           String eventType, String status, String providerCode, String error, Instant now) { }
}
