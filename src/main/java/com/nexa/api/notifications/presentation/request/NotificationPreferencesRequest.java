package com.nexa.api.notifications.presentation.request;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;

import java.util.List;

public record NotificationPreferencesRequest(List<PreferenceRequest> preferences, long version) {
	public NotificationPreferencesRequest {
		preferences = preferences == null ? List.of() : List.copyOf(preferences);
	}

	public NotificationPreferencesView toModel() {
		return new NotificationPreferencesView(preferences.stream().map(PreferenceRequest::toModel).toList(), version);
	}

	public record PreferenceRequest(String eventCategory, String channel, boolean enabled, long version) {
		NotificationPreferenceView toModel() { return new NotificationPreferenceView(eventCategory, channel, enabled, version); }
	}
}
