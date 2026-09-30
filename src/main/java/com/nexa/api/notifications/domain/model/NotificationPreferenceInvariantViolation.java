package com.nexa.api.notifications.domain.model;

public final class NotificationPreferenceInvariantViolation extends IllegalArgumentException {
	public NotificationPreferenceInvariantViolation(String message) {
		super(message);
	}
}
