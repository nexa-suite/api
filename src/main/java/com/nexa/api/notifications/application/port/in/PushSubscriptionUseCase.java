package com.nexa.api.notifications.application.port.in;

import com.nexa.api.notifications.application.publicapi.PushSubscriptionView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Existing native subscription commands, bound by the runtime to one persistence owner. */
@org.springframework.modulith.NamedInterface(value = "notifications-in", propagate = false)
public interface PushSubscriptionUseCase {
    PushSubscriptionView register(CurrentAccessContext context, String nativeClient,
            String installationId, String platform, String providerToken, String idempotencyKey);

    PushSubscriptionView disable(CurrentAccessContext context, String nativeClient,
            UUID subscriptionId, String idempotencyKey, boolean unregister);
}
