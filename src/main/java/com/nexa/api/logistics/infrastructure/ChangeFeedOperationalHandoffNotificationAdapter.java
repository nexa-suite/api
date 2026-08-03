package com.nexa.api.logistics.infrastructure;

import com.nexa.api.logistics.application.port.OperationalHandoffNotificationPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Durable scoped notification adapter backed by the existing tenant-scoped change feed. */
@Component
@Profile("!test")
public final class ChangeFeedOperationalHandoffNotificationAdapter implements OperationalHandoffNotificationPort {
    private final ChangeEventPersistencePort changeFeed;

    public ChangeFeedOperationalHandoffNotificationAdapter(ChangeEventPersistencePort changeFeed) {
        this.changeFeed = changeFeed;
    }

    @Override
    public void notify(Notification notification) {
        changeFeed.append(notification.tenantId(), notification.workspaceId(), notification.clientAccountId(),
                "dispatch_order", notification.dispatchOrderId(), notification.eventType(),
                notification.publicStatus(), notification.occurredAtEpochMillis(), false);
    }
}
