package com.nexa.api.notifications.domain;

import com.nexa.api.notifications.application.publicapi.NotificationPreferenceAccess;
import com.nexa.api.notifications.domain.model.NotificationPreference;
import com.nexa.api.notifications.domain.model.NotificationPreferenceInvariantViolation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationPreferenceTests {
	@Test
	void notificationOwnerNormalizesAndValidatesPreferenceVocabulary() {
		assertThat(new NotificationPreference(" order_status ", " in_app ", true, 3))
				.isEqualTo(new NotificationPreference("ORDER_STATUS", "IN_APP", true, 3));
		assertThat(NotificationPreference.eventCategories())
				.containsExactly("TEMPERATURE_ALERT", "DOCUMENT_REMINDER", "ORDER_STATUS", "INVITATION");
		assertThat(NotificationPreference.channels()).containsExactly("IN_APP", "EMAIL");
	}

	@Test
	void notificationPreferenceAccessExposesValidatedOwnerValues() {
		assertThat(new NotificationPreferenceAccess.Preference(" invitation ", " email ", false, 2))
				.isEqualTo(new NotificationPreferenceAccess.Preference("INVITATION", "EMAIL", false, 2));
	}

	@Test
	void unsupportedCategoriesAndChannelsAreRejectedByNotificationOwner() {
		assertThatThrownBy(() -> new NotificationPreference("UNKNOWN", "EMAIL", true, 0))
				.isInstanceOf(NotificationPreferenceInvariantViolation.class)
				.hasMessage("Unsupported notification category");
		assertThatThrownBy(() -> new NotificationPreference("ORDER_STATUS", "SMS", true, 0))
				.isInstanceOf(NotificationPreferenceInvariantViolation.class)
				.hasMessage("Unsupported notification channel");
		assertThatThrownBy(() -> new NotificationPreferenceAccess.Preference(" ", "EMAIL", true, 0))
				.isInstanceOf(NotificationPreferenceInvariantViolation.class);
	}
}
