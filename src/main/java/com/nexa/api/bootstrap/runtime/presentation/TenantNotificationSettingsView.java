package com.nexa.api.bootstrap.runtime.presentation;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "NotificationSettingsView")
public record TenantNotificationSettingsView(List<Preference> preferences, long version) {
    public TenantNotificationSettingsView {
        preferences = List.copyOf(preferences);
    }

    public static TenantNotificationSettingsView from(NotificationPreferencesView value) {
        var preferences = value.preferences().stream()
                .map(preference -> new Preference(preference.eventCategory(), preference.channel(),
                        preference.enabled(), preference.version()))
                .toList();
        return new TenantNotificationSettingsView(preferences, value.version());
    }

    public NotificationPreferencesView toPreferences(long expectedVersion) {
        var values = preferences.stream()
                .map(preference -> new NotificationPreferenceView(preference.eventCategory(), preference.channel(),
                        preference.enabled(), preference.version()))
                .toList();
        return new NotificationPreferencesView(values, expectedVersion);
    }

    @Schema(name = "NotificationPreferenceView")
    public record Preference(String eventCategory, String channel, boolean enabled, long version) { }
}
