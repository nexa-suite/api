package com.nexa.api.notifications.application.port.out;

import java.util.UUID;

/** Provider-neutral boundary. Concrete provider selection is intentionally deferred. */
public interface PushProviderPort {
    DeliveryResult deliver(Delivery delivery);

    /** Stable event/subscription key for providers that support idempotent delivery. */
    record Delivery(UUID subscriptionId, String eventId, String eventType, String category,
                    String title, String message, String deepLink, String deliveryKey) { }

    record DeliveryResult(String status, String providerCode, String error) { }
}
