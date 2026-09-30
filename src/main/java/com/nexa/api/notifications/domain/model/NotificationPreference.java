package com.nexa.api.notifications.domain.model;

import java.util.List;
import java.util.Locale;

/** A validated notification preference owned by the Notifications context. */
public record NotificationPreference(String eventCategory, String channel, boolean enabled, long version) {
	private static final List<String> EVENT_CATEGORIES = List.of(
			"TEMPERATURE_ALERT", "DOCUMENT_REMINDER", "ORDER_STATUS", "INVITATION");
	private static final List<String> CHANNELS = List.of("IN_APP", "EMAIL");

	public NotificationPreference {
		eventCategory = normalize(eventCategory, EVENT_CATEGORIES, "category");
		channel = normalize(channel, CHANNELS, "channel");
	}

	public static List<String> eventCategories() {
		return EVENT_CATEGORIES;
	}

	public static List<String> channels() {
		return CHANNELS;
	}

	private static String normalize(String value, List<String> allowed, String label) {
		if (value == null || value.isBlank()) {
			throw new NotificationPreferenceInvariantViolation("Notification preference " + label + " is required");
		}
		String normalized = value.strip().toUpperCase(Locale.ROOT);
		if (!allowed.contains(normalized)) {
			throw new NotificationPreferenceInvariantViolation("Unsupported notification " + label);
		}
		return normalized;
	}
}
