package com.nexa.api.notifications.application.port.out;

import java.util.UUID;

/** Provider-neutral boundary for deferred and Tenant-bound delivery providers. */
public interface PushProviderPort {
    DeliveryResult deliver(Delivery delivery);

    /** Stable event/subscription key for providers that support idempotent delivery. */
    record Delivery(UUID tenantId, UUID workspaceId, UUID subscriptionId, String eventId, String eventType, String category,
                    String title, String message, String deepLink, String deliveryKey) {
        public Delivery(UUID subscriptionId, String eventId, String eventType, String category,
                        String title, String message, String deepLink, String deliveryKey) {
            this(null, null, subscriptionId, eventId, eventType, category, title, message, deepLink, deliveryKey);
        }
    }

    record DeliveryResult(String status, String providerCode, String error, java.time.Instant retryAfter) {
        public DeliveryResult(String status, String providerCode, String error) {
            this(status, providerCode, error, null);
        }
    }
}
