package com.nexa.api.notifications.application.publicapi;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Exact-scope BC-10 preference operations for the existing Tenant Configuration HTTP edge. */
@org.springframework.modulith.NamedInterface(value = "notification-preferences", propagate = false)
public interface TenantNotificationSettingsCommands {
    NotificationPreferencesView settings(CurrentAccessContext context, UUID workspaceId);

    NotificationPreferencesView updateSettings(CurrentAccessContext context, UUID workspaceId,
            NotificationPreferencesView request, long expectedVersion, String correlationId);
}
