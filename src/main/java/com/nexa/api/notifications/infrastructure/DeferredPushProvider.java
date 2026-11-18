package com.nexa.api.notifications.infrastructure;

import com.nexa.api.notifications.application.port.out.PushProviderPort;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default safe fallback when no delivery provider is selected. */
@Component
@ConditionalOnProperty(prefix = "nexa.notifications.push", name = "provider", havingValue = "deferred", matchIfMissing = true)
@Profile("!test")
public final class DeferredPushProvider implements PushProviderPort {
    @Override
    public DeliveryResult deliver(Delivery delivery) {
        return new DeliveryResult("DEFERRED", "PROVIDER_NOT_CONFIGURED", null);
    }
}
