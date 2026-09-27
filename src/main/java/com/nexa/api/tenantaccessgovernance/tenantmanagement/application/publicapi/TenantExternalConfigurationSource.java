package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.List;
import java.util.UUID;

/** Same-process configuration facts composed from their owning bounded contexts. */
public interface TenantExternalConfigurationSource {
    List<Preference> notificationPreferences(UUID workspaceId);

    long notificationVersion(UUID workspaceId);

    int updateNotificationPreference(UUID workspaceId, Preference preference);

    void ensureNotificationDefaults(UUID workspaceId);

    long salesTransactionCount(UUID tenantId);

    record Preference(String eventCategory, String channel, boolean enabled, long version) { }
}
