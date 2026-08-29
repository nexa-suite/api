package com.nexa.api.notifications.application.service;

import com.nexa.api.notifications.application.exception.NotificationOperationException;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;
import com.nexa.api.notifications.application.port.out.PushProviderPort;
import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Routes existing notification facts to opted-in subscriptions without provider I/O in the business transaction. */
@Service
@Profile("!test")
public final class PushRoutingService {
    private final PushSubscriptionPersistencePort subscriptions;
    private final PushProviderPort provider;
    private final TransactionTemplate attemptTransactions;

    public PushRoutingService(PushSubscriptionPersistencePort subscriptions, PushProviderPort provider) {
        this(subscriptions, provider, null);
    }

    @Autowired
    public PushRoutingService(PushSubscriptionPersistencePort subscriptions, PushProviderPort provider,
                              PlatformTransactionManager transactionManager) {
        this.subscriptions = subscriptions;
        this.provider = provider;
        if (transactionManager == null) {
            this.attemptTransactions = null;
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.attemptTransactions = template;
        }
    }

    /** Compatibility path used by the legacy after-commit listener. */
    public void route(NotificationProjection event, String category, String title, String message, String deepLink) {
        route(event, category, title, message, deepLink, false);
    }

    /** Durable outbox path; deferred provider work remains retryable/dead-letterable. */
    public void routeDurable(NotificationProjection event, String category, String title, String message, String deepLink) {
        route(event, category, title, message, deepLink, true);
    }

    private void route(NotificationProjection event, String category, String title, String message, String deepLink,
                       boolean retryDeferred) {
        UUID tenant = uuid(event.tenantId());
        UUID workspace = uuid(event.workspaceId());
        if (tenant == null || workspace == null) return;
        RuntimeException retryableFailure = null;
        for (String recipient : event.recipientMembershipIds()) {
            UUID membership = uuid(recipient);
            if (membership == null) continue;
            for (PushSubscriptionPersistencePort.PushSubscription subscription
                    : subscriptions.activeForRecipient(tenant, workspace, membership)) {
                PushSubscriptionPersistencePort.DeliveryClaim claim;
                try {
                    claim = subscriptions.claimDelivery(tenant, workspace, subscription.id(), event.eventId(),
                            deliveryKey(event.eventId(), subscription.id()), java.time.Instant.now());
                    if (claim == null || claim.status() == null) {
                        throw new NotificationOperationException("PUSH_DELIVERY_CLAIM_UNAVAILABLE", false);
                    }
                } catch (RuntimeException exception) {
                    retryableFailure = exception;
                    continue;
                }
                if (claim.status() == PushSubscriptionPersistencePort.DeliveryClaimStatus.ALREADY_SENT) continue;
                if (claim.status() == PushSubscriptionPersistencePort.DeliveryClaimStatus.BUSY) {
                    if (retryDeferred) retryableFailure = new NotificationOperationException("PUSH_DELIVERY_RETRYABLE", false);
                    continue;
                }

                PushProviderPort.DeliveryResult result;
                boolean sent = false;
                java.time.Instant attemptAt = java.time.Instant.now();
                try {
                    result = provider.deliver(new PushProviderPort.Delivery(subscription.id(), event.eventId(),
                            event.eventType(), category, title, message, deepLink,
                            deliveryKey(event.eventId(), subscription.id())));
                    if (result == null) {
                        result = new PushProviderPort.DeliveryResult("RETRYABLE", "PROVIDER_INVALID_RESULT", "Provider returned no result");
                    }
                } catch (RuntimeException exception) {
                    result = new PushProviderPort.DeliveryResult("RETRYABLE", "PROVIDER_EXCEPTION", "Provider delivery failed");
                }
                try {
                    String status = normalizedStatus(result.status());
                    var attempt = new PushSubscriptionPersistencePort.DeliveryAttempt(
                            tenant, workspace, subscription.id(), event.eventId(), event.eventType(),
                            status, normalizedProviderCode(result.providerCode()), normalizedError(result.error()), attemptAt);
                    recordAttempt(attempt);
                    sent = "SENT".equals(status);
                    if ("RETRYABLE".equals(status) || (retryDeferred && "DEFERRED".equals(status))) {
                        retryableFailure = new NotificationOperationException("PUSH_DELIVERY_RETRYABLE", false);
                    }
                } catch (RuntimeException exception) {
                    // The push work item is already separate from the durable
                    // in-app projection. Propagate so the canonical outbox can
                    // retry the delivery or move it to its dead-letter state.
                    retryableFailure = exception;
                } finally {
                    try {
                        subscriptions.completeDelivery(tenant, workspace, subscription.id(), event.eventId(),
                                claim.claimToken(), sent, java.time.Instant.now());
                    } catch (RuntimeException exception) {
                        retryableFailure = exception;
                    }
                }
            }
        }
        if (retryableFailure != null) throw retryableFailure;
    }

    private void recordAttempt(PushSubscriptionPersistencePort.DeliveryAttempt attempt) {
        if (attemptTransactions == null) {
            subscriptions.recordAttempt(attempt);
            return;
        }
        attemptTransactions.executeWithoutResult(transaction -> subscriptions.recordAttempt(attempt));
    }

    private static String normalizedStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "DEFERRED", "SENT", "FAILED", "RETRYABLE" -> normalized;
            default -> "FAILED";
        };
    }

    private static String normalizedProviderCode(String value) {
        if (value == null || value.isBlank()) return "PROVIDER_UNKNOWN";
        String normalized = value.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static String normalizedError(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    private static String deliveryKey(String eventId, UUID subscriptionId) {
        return "push|" + eventId + "|" + subscriptionId;
    }

    private static UUID uuid(String value) {
        try { return value == null ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
