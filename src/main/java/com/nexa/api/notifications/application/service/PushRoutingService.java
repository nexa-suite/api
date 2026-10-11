package com.nexa.api.notifications.application.service;

import com.nexa.api.notifications.application.exception.NotificationOperationException;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.NotificationProjection;
import com.nexa.api.notifications.application.port.out.PushProviderPort;
import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import com.nexa.api.notifications.application.port.out.PushTransactionScopeGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Routes existing notification facts to opted-in subscriptions without provider I/O in the business transaction. */
@Service
@Profile("!test")
public final class PushRoutingService {
    private final PushSubscriptionPersistencePort subscriptions;
    private final PushProviderPort provider;
    private final PushDeliveryRetryPolicy retryPolicy;
    private final TransactionTemplate attemptTransactions;
    private final TransactionTemplate providerWithoutTransaction;
    private final PushTransactionScopeGuard scopeGuard;
    private final UUID scopedTenantId;
    private final UUID scopedWorkspaceId;

    public PushRoutingService(PushSubscriptionPersistencePort subscriptions, PushProviderPort provider) {
        this(subscriptions, provider, null, defaultRetryPolicy(), null, null, null);
    }

    @Autowired
    public PushRoutingService(PushSubscriptionPersistencePort subscriptions, PushProviderPort provider,
                              PlatformTransactionManager transactionManager, PushDeliveryRetryPolicy retryPolicy) {
        this(subscriptions, provider, transactionManager, retryPolicy, null, null, null);
    }

    public PushRoutingService(PushSubscriptionPersistencePort subscriptions, PushProviderPort provider,
                              PlatformTransactionManager transactionManager, PushTransactionScopeGuard scopeGuard,
                              UUID scopedTenantId, UUID scopedWorkspaceId) {
        this(subscriptions, provider, transactionManager, defaultRetryPolicy(), scopeGuard, scopedTenantId,
                scopedWorkspaceId);
    }

    public PushRoutingService(PushSubscriptionPersistencePort subscriptions, PushProviderPort provider,
                              PlatformTransactionManager transactionManager, PushDeliveryRetryPolicy retryPolicy,
                              PushTransactionScopeGuard scopeGuard, UUID scopedTenantId, UUID scopedWorkspaceId) {
        this.subscriptions = subscriptions;
        this.provider = provider;
        this.retryPolicy = java.util.Objects.requireNonNull(retryPolicy, "Push retry policy is required");
        this.scopeGuard = scopeGuard;
        this.scopedTenantId = scopedTenantId;
        this.scopedWorkspaceId = scopedWorkspaceId;
        if ((scopeGuard == null) != (scopedTenantId == null)
                || (scopeGuard == null) != (scopedWorkspaceId == null)) {
            throw new IllegalArgumentException("Tenant push routing scope must be complete");
        }
        if (scopeGuard != null && transactionManager == null) {
            throw new IllegalArgumentException("Scoped Tenant push routing requires its routed transaction manager");
        }
        if (transactionManager == null) {
            this.attemptTransactions = null;
            this.providerWithoutTransaction = null;
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.attemptTransactions = template;
            TransactionTemplate providerTemplate = new TransactionTemplate(transactionManager);
            providerTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            this.providerWithoutTransaction = providerTemplate;
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
        if (scopedTenantId != null && (!scopedTenantId.equals(tenant) || !scopedWorkspaceId.equals(workspace))) {
            throw new IllegalStateException("Tenant push work escaped its verified Tenant/Workspace binding");
        }
        RuntimeException retryableFailure = null;
        for (String recipient : event.recipientMembershipIds()) {
            UUID membership = uuid(recipient);
            if (membership == null) continue;
            verifyTenantScope();
            for (PushSubscriptionPersistencePort.PushSubscription subscription
                    : subscriptions.activeForRecipient(tenant, workspace, membership)) {
                PushSubscriptionPersistencePort.DeliveryClaim claim;
                try {
                    Instant now = retryPolicy.now();
                    claim = claimDelivery(tenant, workspace, subscription.id(), event.eventId(),
                            deliveryKey(event.eventId(), subscription.id()), maxAttempts(), now,
                            retryPolicy.claimLeaseUntil(now));
                    if (claim == null || claim.status() == null) {
                        throw new NotificationOperationException("PUSH_DELIVERY_CLAIM_UNAVAILABLE", false);
                    }
                } catch (RuntimeException exception) {
                    retryableFailure = exception;
                    continue;
                }
                if (claim.status() == PushSubscriptionPersistencePort.DeliveryClaimStatus.ALREADY_SENT) continue;
                if (claim.status() == PushSubscriptionPersistencePort.DeliveryClaimStatus.DEAD_LETTER) continue;
                if (claim.status() == PushSubscriptionPersistencePort.DeliveryClaimStatus.BUSY) {
                    if (retryDeferred) retryableFailure = new NotificationOperationException("PUSH_DELIVERY_RETRYABLE", false);
                    continue;
                }

                PushProviderPort.DeliveryResult result;
                boolean sent = false;
                try {
                    PushProviderPort.Delivery delivery = new PushProviderPort.Delivery(subscription.id(), event.eventId(),
                            event.eventType(), category, title, message, deepLink,
                            deliveryKey(event.eventId(), subscription.id()));
                    result = deliverWithoutTransaction(delivery);
                    if (result == null) {
                        result = new PushProviderPort.DeliveryResult("RETRYABLE", "PROVIDER_INVALID_RESULT", "Provider returned no result");
                    }
                } catch (RuntimeException exception) {
                    result = new PushProviderPort.DeliveryResult("RETRYABLE", "PROVIDER_EXCEPTION", "Provider delivery failed");
                }
                Instant attemptAt = retryPolicy.now();
                try {
                    String status = normalizedStatus(result.status());
                    var attempt = new PushSubscriptionPersistencePort.DeliveryAttempt(
                            tenant, workspace, subscription.id(), event.eventId(), event.eventType(),
                            status, normalizedProviderCode(result.providerCode()), normalizedError(result.error()), attemptAt);
                    sent = "SENT".equals(status);
                    boolean retryable = "RETRYABLE".equals(status)
                            || (retryDeferred && "DEFERRED".equals(status))
                            || (scopeGuard == null && !sent);
                    Instant retryAt = retryable ? nextRetryAt(claim.attemptNumber(), attemptAt) : null;
                    var completion = completeDelivery(attempt, claim.claimToken(), claim.attemptNumber(),
                            maxAttempts(), retryable, retryAt);
                    if (completion == PushSubscriptionPersistencePort.DeliveryCompletion.RETRY_SCHEDULED
                            && (retryDeferred || "RETRYABLE".equals(status))) {
                        retryableFailure = new NotificationOperationException("PUSH_DELIVERY_RETRYABLE", false);
                    }
                } catch (RuntimeException exception) {
                    // Keep the outbox item retryable if attempt evidence or fenced finalization fails.
                    retryableFailure = exception;
                }
            }
        }
        if (retryableFailure != null) throw retryableFailure;
    }

    private PushSubscriptionPersistencePort.DeliveryClaim claimDelivery(UUID tenant, UUID workspace,
            UUID subscriptionId, String eventId, String deliveryKey, int maxAttempts, Instant now, Instant leaseUntil) {
        if (attemptTransactions == null) {
            return subscriptions.claimDelivery(tenant, workspace, subscriptionId, eventId, deliveryKey,
                    maxAttempts, now, leaseUntil);
        }
        return attemptTransactions.execute(status -> {
            verifyTenantScope();
            return subscriptions.claimDelivery(tenant, workspace, subscriptionId, eventId, deliveryKey,
                    maxAttempts, now, leaseUntil);
        });
    }

    private PushSubscriptionPersistencePort.DeliveryCompletion completeDelivery(
            PushSubscriptionPersistencePort.DeliveryAttempt attempt, UUID claimToken, int attemptNumber,
            int maxAttempts, boolean retryable, Instant retryAt) {
        if (attemptTransactions == null) {
            return subscriptions.completeDelivery(attempt, claimToken, attemptNumber, maxAttempts, retryable, retryAt);
        }
        return attemptTransactions.execute(status -> {
            verifyTenantScope();
            return subscriptions.completeDelivery(attempt, claimToken, attemptNumber, maxAttempts, retryable, retryAt);
        });
    }

    private PushProviderPort.DeliveryResult deliverWithoutTransaction(PushProviderPort.Delivery delivery) {
        if (providerWithoutTransaction == null) return provider.deliver(delivery);
        return providerWithoutTransaction.execute(status -> provider.deliver(delivery));
    }

    private int maxAttempts() {
        return scopeGuard == null ? Integer.MAX_VALUE : retryPolicy.maxAttempts();
    }

    private Instant nextRetryAt(int attemptNumber, Instant attemptAt) {
        return scopeGuard == null ? attemptAt.plusNanos(1) : retryPolicy.retryAt(attemptNumber, attemptAt);
    }

    private void verifyTenantScope() {
        if (scopeGuard == null) return;
        scopeGuard.verify(scopedTenantId, scopedWorkspaceId);
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

    private static PushDeliveryRetryPolicy defaultRetryPolicy() {
        return new PushDeliveryRetryPolicy(PushDeliveryRetryPolicy.DEFAULT_MAX_ATTEMPTS,
                PushDeliveryRetryPolicy.DEFAULT_MAX_DELAY_SECONDS, Clock.systemUTC(), Math::random);
    }
}
