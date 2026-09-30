package com.nexa.api.notifications.application.publicapi;

import java.time.Instant;
import java.util.Set;

/** Immutable facts accepted by the notification projection workflow. */
public final class NotificationProjectionModels {
    private NotificationProjectionModels() { }
	public record NotificationProjection(String eventId, String tenantId, String workspaceId,
			String clientAccountId, String aggregateType, String aggregateId, String eventType,
			String publicStatus, Instant occurredAt, Set<String> recipientMembershipIds) {
		public NotificationProjection {
			recipientMembershipIds = Set.copyOf(recipientMembershipIds);
		}
	}

	public record PushNotificationCandidate(NotificationProjection projection, String category, String title,
			String message, String deepLink) { }

}
