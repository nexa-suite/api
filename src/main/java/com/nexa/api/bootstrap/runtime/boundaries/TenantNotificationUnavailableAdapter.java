package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPage;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.in.PushSubscriptionUseCase;
import com.nexa.api.notifications.application.publicapi.PushSubscriptionView;
import com.nexa.api.notifications.application.publicapi.TenantNotificationSettingsCommands;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Fails closed when HTTP notification requests have no verified Tenant persistence adapter. */
public final class TenantNotificationUnavailableAdapter implements NotificationUseCase, PushSubscriptionUseCase,
        TenantNotificationSettingsCommands {
    @Override
    public NotificationPage inbox(CurrentAccessContext context, boolean unreadOnly, int limit) {
        throw unavailable();
    }

    @Override
    public long unreadCount(CurrentAccessContext context) {
        throw unavailable();
    }

    @Override
    public void markRead(CurrentAccessContext context, String notificationId, boolean read) {
        throw unavailable();
    }

    @Override
    public void markAllRead(CurrentAccessContext context) {
        throw unavailable();
    }

    @Override
    public NotificationPreferencesView preferences(CurrentAccessContext context) {
        throw unavailable();
    }

    @Override
    public NotificationPreferencesView updatePreferences(CurrentAccessContext context,
            NotificationPreferencesView request) {
        throw unavailable();
    }

    @Override
    public PushSubscriptionView register(CurrentAccessContext context, String nativeClient, String installationId,
            String platform, String providerToken, String idempotencyKey) {
        throw unavailable();
    }

    @Override
    public PushSubscriptionView disable(CurrentAccessContext context, String nativeClient, UUID subscriptionId,
            String idempotencyKey, boolean unregister) {
        throw unavailable();
    }

    @Override
    public NotificationPreferencesView settings(CurrentAccessContext context, UUID workspaceId) {
        throw unavailable();
    }

    @Override
    public NotificationPreferencesView updateSettings(CurrentAccessContext context, UUID workspaceId,
            NotificationPreferencesView request, long expectedVersion, String correlationId) {
        throw unavailable();
    }

    private static TechnicalFailureException unavailable() {
        return new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                "Tenant notification database routing is unavailable");
    }
}
