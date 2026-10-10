package com.nexa.api.notifications.application.service;

import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.notifications.application.port.out.NotificationProjectionSourceEventQuery;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.NotificationProjection;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.PushNotificationCandidate;
import com.nexa.api.notifications.application.publicapi.PreflightedNotificationRecipients;

import java.util.Objects;
import java.util.UUID;

/** Applies the central recipient preflight and exact Tenant source check before BC-10 projection. */
public final class TenantNotificationProjectionService implements NotificationProjectionPort {
    private final PreflightedNotificationRecipients recipients;
    private final NotificationProjectionSourceEventQuery sourceEvents;
    private final NotificationService notifications;

    public TenantNotificationProjectionService(PreflightedNotificationRecipients recipients,
            NotificationProjectionSourceEventQuery sourceEvents, NotificationService notifications) {
        this.recipients = Objects.requireNonNull(recipients, "Central recipient preflight is required");
        this.sourceEvents = Objects.requireNonNull(sourceEvents, "Tenant source-event query is required");
        this.notifications = Objects.requireNonNull(notifications, "Notification application service is required");
    }

    @Override
    public void project(NotificationProjection event) {
        Objects.requireNonNull(event, "Notification projection is required");
        UUID eventId = UUID.fromString(event.eventId());
        UUID tenantId = UUID.fromString(event.tenantId());
        UUID workspaceId = UUID.fromString(event.workspaceId());
        UUID aggregateId = UUID.fromString(event.aggregateId());
        if (!recipients.tenantId().equals(tenantId) || !recipients.workspaceId().equals(workspaceId)) {
            throw new IllegalStateException("Notification projection escaped its preflighted Tenant/Workspace");
        }
        for (String recipient : event.recipientMembershipIds()) {
            if (!recipients.allows(tenantId, workspaceId, UUID.fromString(recipient))) {
                throw new IllegalStateException("Notification recipient escaped its central membership/role preflight");
            }
        }
        if (event.sourcePayloadSha256() == null || !event.sourcePayloadSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Tenant notification projection requires its source payload fingerprint");
        }
        NotificationProjectionSourceEventQuery.SourceEvent source = sourceEvents
                .find(eventId, tenantId, workspaceId)
                .orElseThrow(() -> new IllegalStateException(
                        "Tenant notification source event changed or escaped its verified anchor"));
        if (!event.eventType().equals(source.eventType())
                || !event.aggregateType().equals(source.aggregateType())
                || !aggregateId.equals(source.aggregateId())
                || !tenantId.equals(source.tenantId()) || !workspaceId.equals(source.workspaceId())
                || !event.occurredAt().equals(source.occurredAt())
                || !event.sourcePayloadSha256().equals(NotificationProjectionModels.sourcePayloadSha256(source.payloadText()))) {
            throw new IllegalStateException("Tenant notification source event changed or escaped its verified anchor");
        }
        notifications.project(event);
    }

    @Override
    public void deliverPush(PushNotificationCandidate candidate) {
        throw new IllegalStateException("Tenant push delivery is OPEN and unavailable");
    }
}
