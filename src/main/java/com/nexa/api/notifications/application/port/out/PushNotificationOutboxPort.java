package com.nexa.api.notifications.application.port.out;

import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.PushNotificationCandidate;

/** Durable BC-10 handoff to the existing canonical outbox retry discipline. */
public interface PushNotificationOutboxPort {
    void enqueue(PushNotificationCandidate candidate);
}
