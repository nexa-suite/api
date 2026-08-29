package com.nexa.api.notifications.infrastructure;

import com.nexa.api.notifications.application.model.NotificationModels.PushNotificationCandidate;
import com.nexa.api.notifications.application.service.PushRoutingService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Keeps provider I/O after the transaction that commits the notification fact. */
@Component
@Profile("!test")
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
