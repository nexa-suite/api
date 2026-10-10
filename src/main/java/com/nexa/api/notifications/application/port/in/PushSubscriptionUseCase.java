package com.nexa.api.notifications.application.port.in;

import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Existing native subscription commands, bound by the runtime to one persistence owner. */
public interface PushSubscriptionUseCase {
    PushSubscriptionPersistencePort.PushSubscription register(CurrentAccessContext context, String nativeClient,
            String installationId, String platform, String providerToken, String idempotencyKey);

    PushSubscriptionPersistencePort.PushSubscription disable(CurrentAccessContext context, String nativeClient,
            UUID subscriptionId, String idempotencyKey, boolean unregister);
}
