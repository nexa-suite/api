package com.nexa.api.notifications.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class NotificationModels {
	private NotificationModels() { }

	@org.springframework.modulith.NamedInterface(value = "notifications-in", propagate = false)
	public record NotificationView(String id, String category, String title, String message, String deepLink,
			String subjectType, String subjectId, Instant createdAt, Instant readAt) { }

	@org.springframework.modulith.NamedInterface(value = "notifications-in", propagate = false)
	public record NotificationPage(List<NotificationView> items, long unreadCount, int limit) {
		public NotificationPage {
			items = List.copyOf(items);
		}
	}

	@org.springframework.modulith.NamedInterface(value = "notifications-in", propagate = false)
	public record NotificationPreferenceView(String eventCategory, String channel, boolean enabled, long version) { }

	@org.springframework.modulith.NamedInterface(value = "notifications-in", propagate = false)
	public record NotificationPreferencesView(List<NotificationPreferenceView> preferences, long version) {
		public NotificationPreferencesView {
			preferences = List.copyOf(preferences);
		}
	}


	public record ProjectedNotification(String eventId, String tenantId, String workspaceId,
			String recipientMembershipId, String category, String title, String message, String deepLink,
			String subjectType, String subjectId, Instant createdAt) { }
}
