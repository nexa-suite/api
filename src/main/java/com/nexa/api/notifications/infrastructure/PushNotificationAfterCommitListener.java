package com.nexa.api.notifications.infrastructure;

import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.PushNotificationCandidate;
import com.nexa.api.notifications.application.service.PushRoutingService;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Keeps provider I/O after the transaction that commits the notification fact. */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.notifications.push", name = "provider", havingValue = "deferred", matchIfMissing = true)
public final class PushNotificationAfterCommitListener {
    private final PushRoutingService routing;

    public PushNotificationAfterCommitListener(PushRoutingService routing) {
        this.routing = routing;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void route(PushNotificationCandidate candidate) {
        routing.route(candidate.projection(), candidate.category(), candidate.title(), candidate.message(), candidate.deepLink());
    }
}
