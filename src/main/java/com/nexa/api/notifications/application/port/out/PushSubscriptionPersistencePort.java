package com.nexa.api.notifications.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BC-10 persistence boundary for provider-neutral native push routing. */
public interface PushSubscriptionPersistencePort {
    PushSubscription register(RegisterRequest request);
    PushSubscription disable(DisableRequest request);
    List<PushSubscription> activeForRecipient(UUID tenantId, UUID workspaceId, UUID recipientMembershipId);
    DeliveryClaim claimDelivery(UUID tenantId, UUID workspaceId, UUID subscriptionId, String eventId,
                                String deliveryKey, int maxAttempts, Instant now, Instant leaseUntil);
    DeliveryCompletion completeDelivery(DeliveryAttempt attempt, UUID claimToken, int attemptNumber,
                                       int maxAttempts, boolean retryable, Instant retryAt);

    enum DeliveryClaimStatus { CLAIMED, ALREADY_SENT, BUSY, DEAD_LETTER }
    enum DeliveryCompletion { SENT, RETRY_SCHEDULED, DEAD_LETTER }

    record DeliveryClaim(DeliveryClaimStatus status, UUID claimToken, int attemptNumber) {
        public DeliveryClaim {
            if (status == null) throw new IllegalArgumentException("Delivery claim status is required");
            if (status == DeliveryClaimStatus.CLAIMED && claimToken == null) {
                throw new IllegalArgumentException("A claimed delivery requires a claim token");
            }
            if (status == DeliveryClaimStatus.CLAIMED && attemptNumber < 1) {
                throw new IllegalArgumentException("A claimed delivery requires a positive attempt number");
            }
        }
    }

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
