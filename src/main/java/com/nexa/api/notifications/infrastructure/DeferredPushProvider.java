package com.nexa.api.notifications.infrastructure;

import com.nexa.api.notifications.application.port.out.PushProviderPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Candidate adapter: durable routing is executable, while provider selection remains deferred. */
@Component
@Profile("!test")
public final class DeferredPushProvider implements PushProviderPort {
    @Override
    public DeliveryResult deliver(Delivery delivery) {
        return new DeliveryResult("DEFERRED", "PROVIDER_NOT_CONFIGURED", null);
    }
}
