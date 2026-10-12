package com.nexa.api.notifications.application.service;

import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.NotificationProjection;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.PushNotificationCandidate;

import java.util.Objects;

/** Implements the inbound projection contract through BC-10 application behavior. */
public final class NotificationProjectionService implements NotificationProjectionPort {
    private final NotificationService notifications;

    public NotificationProjectionService(NotificationService notifications) {
        this.notifications = Objects.requireNonNull(notifications, "Notification application service is required");
    }

    @Override
    public void project(NotificationProjection event) {
        notifications.project(event);
    }

    @Override
    public void deliverPush(PushNotificationCandidate candidate) {
        notifications.deliverPush(candidate);
    }
}
