package com.nexa.api.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;
import java.util.Set;

public record NotificationPreference(String eventCategory, String channel, boolean enabled, long version) {
	public NotificationPreference {
		eventCategory = required(eventCategory).toUpperCase(Locale.ROOT);
		channel = required(channel).toUpperCase(Locale.ROOT);
		if (!Set.of("TEMPERATURE_ALERT", "DOCUMENT_REMINDER", "ORDER_STATUS", "INVITATION").contains(eventCategory)) throw new TenantManagementInvariantViolation("Unsupported notification category");
		if (!Set.of("IN_APP", "EMAIL").contains(channel)) throw new TenantManagementInvariantViolation("Unsupported notification channel");
	}

	private static String required(String value) {
		if (value == null || value.isBlank()) throw new TenantManagementInvariantViolation("Notification preference is required");
		return value.strip();
	}
}
