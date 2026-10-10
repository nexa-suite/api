package com.nexa.api.notifications.application;

import com.nexa.api.notifications.application.port.out.NotificationProjectionSourceEventQuery;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.NotificationProjection;
import com.nexa.api.notifications.application.publicapi.PreflightedNotificationRecipients;
import com.nexa.api.notifications.application.service.TenantNotificationProjectionService;
import com.nexa.api.notifications.application.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantNotificationProjectionServiceTests {
    private static final UUID TENANT = UUID.fromString("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10");
    private static final UUID WORKSPACE = UUID.fromString("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17");
    private static final UUID EVENT = UUID.fromString("fe9e50a5-3c78-487c-a090-63a45959df4a");
    private static final UUID AGGREGATE = UUID.fromString("0d5b76ed-2365-4f5b-8bfb-6b4f8c8de38e");
    private static final UUID RECIPIENT = UUID.fromString("24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-10T12:00:00Z");
    private static final String PAYLOAD = "{\"status\":\"CONFIRMED\"}";

    @Test
    void projectsOnlyAfterExactTenantSourceAndRecipientChecks() {
        var recipients = new PreflightedNotificationRecipients(TENANT, WORKSPACE, Set.of(RECIPIENT));
        var sourceEvents = mock(NotificationProjectionSourceEventQuery.class);
        var notifications = mock(NotificationService.class);
        var event = event(RECIPIENT, NotificationProjectionModels.sourcePayloadSha256(PAYLOAD));
        when(sourceEvents.find(EVENT, TENANT, WORKSPACE)).thenReturn(Optional.of(sourceEvent()));

        new TenantNotificationProjectionService(recipients, sourceEvents, notifications).project(event);

        verify(notifications).project(event);
    }

    @Test
    void rejectsRecipientOutsideCentralPreflightBeforeReadingSource() {
        var sourceEvents = mock(NotificationProjectionSourceEventQuery.class);
        var notifications = mock(NotificationService.class);
        var service = new TenantNotificationProjectionService(
                new PreflightedNotificationRecipients(TENANT, WORKSPACE, Set.of()), sourceEvents, notifications);

        assertThatThrownBy(() -> service.project(event(RECIPIENT,
                NotificationProjectionModels.sourcePayloadSha256(PAYLOAD))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("central membership/role preflight");

        verify(sourceEvents, never()).find(EVENT, TENANT, WORKSPACE);
        verify(notifications, never()).project(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsChangedSourcePayloadBeforeProjection() {
        var sourceEvents = mock(NotificationProjectionSourceEventQuery.class);
        var notifications = mock(NotificationService.class);
        when(sourceEvents.find(EVENT, TENANT, WORKSPACE)).thenReturn(Optional.of(sourceEvent()));
        var event = event(RECIPIENT, "0".repeat(64));

        assertThatThrownBy(() -> new TenantNotificationProjectionService(
                new PreflightedNotificationRecipients(TENANT, WORKSPACE, Set.of(RECIPIENT)), sourceEvents, notifications)
                .project(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source event changed");

        verify(notifications, never()).project(org.mockito.ArgumentMatchers.any());
    }

    private static NotificationProjection event(UUID recipient, String payloadHash) {
        return new NotificationProjection(EVENT.toString(), TENANT.toString(), WORKSPACE.toString(), null,
                "SalesOrder", AGGREGATE.toString(), "SALES_ORDER_CONFIRMED", "CONFIRMED", OCCURRED_AT,
                Set.of(recipient.toString()), payloadHash);
    }

    private static NotificationProjectionSourceEventQuery.SourceEvent sourceEvent() {
        return new NotificationProjectionSourceEventQuery.SourceEvent("SALES_ORDER_CONFIRMED", "SalesOrder",
                AGGREGATE, TENANT, WORKSPACE, OCCURRED_AT, PAYLOAD);
    }
}
