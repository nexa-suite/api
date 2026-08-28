package com.nexa.api.notifications.application.port.out;

import java.util.UUID;

/** Provider-neutral boundary. Concrete provider selection is intentionally deferred. */
public interface PushProviderPort {
    DeliveryResult deliver(Delivery delivery);

    record Delivery(UUID subscriptionId, String eventId, String eventType, String category,
                    String title, String message, String deepLink) { }

    record DeliveryResult(String status, String providerCode, String error) { }
}
