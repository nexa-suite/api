package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;
import com.nexa.api.notifications.application.model.NotificationModels.PushNotificationCandidate;
import com.nexa.api.notifications.application.port.out.PushNotificationOutboxPort;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Stores push delivery work in the existing canonical outbox. This is an
 * internal delivery command, not a new business fact or integration event.
 */
@Repository
@Profile("!test")
public class JdbcPushNotificationOutboxAdapter implements PushNotificationOutboxPort {
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "PURCHASE_REQUEST_SUBMITTED", "PURCHASE_REQUEST_APPROVED", "SALES_ORDER_CONFIRMED",
            "DISPATCH_DELIVERED", "DELIVERY_COMPLETED", "POD_COMPLETED", "PAYMENT_SUCCEEDED");

    private final CanonicalOutboxPort outbox;

    public JdbcPushNotificationOutboxAdapter(CanonicalOutboxPort outbox) {
        this.outbox = outbox;
    }

    @Override
    public void enqueue(PushNotificationCandidate candidate) {
        NotificationProjection projection = candidate.projection();
        if (!SUPPORTED_EVENTS.contains(projection.eventType())) return;
        UUID sourceEventId = UUID.fromString(projection.eventId());
        UUID workId = UUID.nameUUIDFromBytes(("notification-push:" + sourceEventId)
                .getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceEventId", projection.eventId());
        payload.put("tenantId", projection.tenantId());
        payload.put("workspaceId", projection.workspaceId());
        payload.put("clientAccountId", projection.clientAccountId());
        payload.put("aggregateType", projection.aggregateType());
        payload.put("aggregateId", projection.aggregateId());
        payload.put("eventType", projection.eventType());
        payload.put("publicStatus", projection.publicStatus());
        payload.put("occurredAt", projection.occurredAt().toString());
        payload.put("recipientMembershipIds", projection.recipientMembershipIds());
        payload.put("category", candidate.category());
        payload.put("title", candidate.title());
        payload.put("message", candidate.message());
        payload.put("deepLink", candidate.deepLink());
        outbox.append("NOTIFICATION_PUSH_DELIVERY_REQUESTED", "NotificationPushDelivery", workId,
                UUID.fromString(projection.tenantId()), UUID.fromString(projection.workspaceId()),
                projection.occurredAt(), "notification-push-" + sourceEventId, sourceEventId, "1.0", payload);
    }
}
