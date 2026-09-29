package com.nexa.api.notifications.application.publicapi;

import com.nexa.api.notifications.domain.model.NotificationPreference;

import java.util.List;
import java.util.UUID;

/** Notification-owned preference reads and writes for same-process consumers. */
public interface NotificationPreferenceAccess {
    List<Preference> notificationPreferences(UUID workspaceId);

    long notificationVersion(UUID workspaceId);

    int updateNotificationPreference(UUID workspaceId, Preference preference);

    void ensureNotificationDefaults(UUID workspaceId);

    record Preference(String eventCategory, String channel, boolean enabled, long version) {
        public Preference {
            NotificationPreference validated = new NotificationPreference(eventCategory, channel, enabled, version);
            eventCategory = validated.eventCategory();
            channel = validated.channel();
        }
    }
}
