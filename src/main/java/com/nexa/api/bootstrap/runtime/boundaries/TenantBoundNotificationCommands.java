package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPage;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.in.PushSubscriptionUseCase;
import com.nexa.api.notifications.application.publicapi.NotificationPreferenceAccess;
import com.nexa.api.notifications.application.publicapi.PreflightedNotificationRecipients;
import com.nexa.api.notifications.application.publicapi.TenantNotificationSettingsCommands;
import com.nexa.api.notifications.application.publicapi.TenantNotificationBusinessBindingsFactory;
import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception.ConcurrencyConflictException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Tenant-enabled HTTP boundary. Every operation uses only the verified request's Tenant route. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.notifications", name = "enabled", havingValue = "true")
public final class TenantBoundNotificationCommands implements NotificationUseCase, PushSubscriptionUseCase,
        TenantNotificationSettingsCommands {
    private final TenantBusinessDatabaseRouter router;
    private final TenantNotificationBusinessBindingsFactory bindings;
    private final SecurityAuditPort audit;
    private final Clock clock;

    public TenantBoundNotificationCommands(TenantBusinessDatabaseRouter router,
            TenantNotificationBusinessBindingsFactory bindings, SecurityAuditPort audit, Clock clock) {
        this.router = router;
        this.bindings = bindings;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public NotificationPage inbox(CurrentAccessContext context, boolean unreadOnly, int limit) {
        return withBindings(context, owner -> owner.notifications().inbox(context, unreadOnly, limit));
    }

    @Override
    public long unreadCount(CurrentAccessContext context) {
        return withBindings(context, owner -> owner.notifications().unreadCount(context));
    }

    @Override
    public void markRead(CurrentAccessContext context, String notificationId, boolean read) {
        withBindings(context, owner -> { owner.notifications().markRead(context, notificationId, read); return null; });
    }

    @Override
    public void markAllRead(CurrentAccessContext context) {
        withBindings(context, owner -> { owner.notifications().markAllRead(context); return null; });
    }

    @Override
    public NotificationPreferencesView preferences(CurrentAccessContext context) {
        return withBindings(context, owner -> owner.notifications().preferences(context));
    }

    @Override
    public NotificationPreferencesView updatePreferences(CurrentAccessContext context,
            NotificationPreferencesView request) {
        return withBindings(context, owner -> owner.notifications().updatePreferences(context, request));
    }

    public NotificationPreferencesView settings(CurrentAccessContext context, UUID workspaceId) {
        context.requirePermission(Permission.TENANT_READ);
        requireWorkspace(context, workspaceId);
        return withPreferences(context, workspaceId, preferences -> {
            preferences.ensureNotificationDefaults(workspaceId);
            List<NotificationPreferenceView> values = preferences
                    .notificationPreferences(workspaceId).stream()
                    .map(TenantBoundNotificationCommands::notificationPreference).toList();
            return new NotificationPreferencesView(values,
                    preferences.notificationVersion(workspaceId));
        });
    }

    public NotificationPreferencesView updateSettings(CurrentAccessContext context, UUID workspaceId,
            NotificationPreferencesView request,
            long expectedVersion, String correlationId) {
        context.requirePermission(PermissionKey.NOTIFICATION_MANAGE_PREFERENCES);
        requireWorkspace(context, workspaceId);
        NotificationPreferencesView updated = withPreferences(context, workspaceId,
                preferences -> {
                    preferences.ensureNotificationDefaults(workspaceId);
                    if (preferences.notificationVersion(workspaceId) != expectedVersion) {
                        throw new ConcurrencyConflictException();
                    }
                    List<NotificationPreferenceAccess.Preference> validated = request.preferences().stream()
                            .map(value -> new NotificationPreferenceAccess.Preference(value.eventCategory(),
                                    value.channel(), value.enabled(), value.version()))
                            .toList();
                    for (NotificationPreferenceAccess.Preference value : validated) {
                        if (preferences.updateNotificationPreference(workspaceId, value) != 1) {
                            throw new ConcurrencyConflictException();
                        }
                    }
                    List<NotificationPreferenceView> values = preferences
                            .notificationPreferences(workspaceId).stream()
                            .map(TenantBoundNotificationCommands::notificationPreference).toList();
                    return new NotificationPreferencesView(values,
                            preferences.notificationVersion(workspaceId));
                });
        audit.append(new SecurityAuditPort.Event("NOTIFICATION_SETTINGS_CHANGED", context.userId().value(), null,
                context.tenantId().value(), workspaceId, context.surface().name(), valueOrUnknown(correlationId),
                "unknown", clock.instant(), java.util.Map.of("section", "notifications")));
        return updated;
    }

    @Override
    public PushSubscriptionPersistencePort.PushSubscription register(CurrentAccessContext context, String nativeClient,
            String installationId, String platform, String providerToken, String idempotencyKey) {
        return withBindings(context, owner -> owner.pushSubscriptions().register(context, nativeClient, installationId,
                platform, providerToken, idempotencyKey));
    }

    @Override
    public PushSubscriptionPersistencePort.PushSubscription disable(CurrentAccessContext context, String nativeClient,
            UUID subscriptionId, String idempotencyKey, boolean unregister) {
        return withBindings(context, owner -> owner.pushSubscriptions().disable(context, nativeClient, subscriptionId,
                idempotencyKey, unregister));
    }

    private <T> T withBindings(CurrentAccessContext context,
            Function<TenantNotificationBusinessBindingsFactory.Bindings, T> operation) {
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        UUID membershipId = context.membershipId().value();
        PreflightedNotificationRecipients recipients = new PreflightedNotificationRecipients(tenantId, workspaceId,
                Set.of(membershipId));
        return router.inTenantSession(context, session -> session.inTransaction(jdbc -> {
            var owner = bindings.bindTo(jdbc, session.transactionManager(), recipients);
            return operation.apply(owner);
        }));
    }

    private <T> T withPreferences(CurrentAccessContext context, UUID workspaceId,
            Function<NotificationPreferenceAccess, T> operation) {
        UUID tenantId = context.tenantId().value();
        UUID membershipId = context.membershipId().value();
        PreflightedNotificationRecipients recipients = new PreflightedNotificationRecipients(tenantId, workspaceId,
                Set.of(membershipId));
        return router.inTenantSession(context, session -> session.inTransaction(jdbc -> {
            var owner = bindings.bindTo(jdbc, session.transactionManager(), recipients);
            return operation.apply(owner.preferences());
        }));
    }

    private static void requireWorkspace(CurrentAccessContext context, UUID requestedWorkspaceId) {
        if (requestedWorkspaceId == null || !requestedWorkspaceId.equals(context.workspaceId().value())) {
            throw new ApiResourceNotFoundException("workspace");
        }
    }

    private static NotificationPreferenceView notificationPreference(
            NotificationPreferenceAccess.Preference preference) {
        return new NotificationPreferenceView(preference.eventCategory(),
                preference.channel(), preference.enabled(), preference.version());
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
