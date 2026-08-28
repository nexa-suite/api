package com.nexa.api.notifications.application;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;
import com.nexa.api.notifications.application.port.out.PushProviderPort;
import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import com.nexa.api.notifications.application.service.PushRoutingService;
import com.nexa.api.notifications.application.service.PushSubscriptionService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushSubscriptionServiceTests {
    private static final UUID TENANT = UUID.fromString("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10");
    private static final UUID WORKSPACE = UUID.fromString("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17");
    private static final UUID MEMBERSHIP = UUID.fromString("24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2");
    private static final UUID USER = UUID.fromString("c9c1f2e5-e4c1-4c9f-9b2d-98f2a40e2b21");

    @Test
    void registersNativeSubscriptionWithOnlyAProviderTokenHash() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        when(persistence.register(any())).thenAnswer(invocation -> new PushSubscriptionPersistencePort.PushSubscription(
                UUID.randomUUID(), MEMBERSHIP, "install-1", "IOS", "PLATFORM", "ENABLED",
                Instant.EPOCH, Instant.EPOCH, 0));

        new PushSubscriptionService(persistence).register(context(), "NATIVE", "install-1", "ios",
                "provider-secret-token", "push-register-1");

        var request = org.mockito.ArgumentCaptor.forClass(PushSubscriptionPersistencePort.RegisterRequest.class);
        verify(persistence).register(request.capture());
        assertThat(request.getValue().tokenHash()).isEqualTo(sha256("provider-secret-token"));
        assertThat(request.getValue().tokenHash()).doesNotContain("provider-secret-token");
        assertThat(request.getValue().surface()).isEqualTo("PLATFORM");
    }

    @Test
    void recordsFailedProviderAttemptWithoutLeakingProviderFailureIntoProjection() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        UUID subscriptionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "ANDROID", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        doThrow(new IllegalStateException("provider unavailable")).when(provider).deliver(any());

        assertThatThrownBy(() -> new PushRoutingService(persistence, provider).route(new NotificationProjection(
                eventId.toString(), TENANT.toString(), WORKSPACE.toString(), null, "SalesOrder", UUID.randomUUID().toString(),
                "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(), Set.of(MEMBERSHIP.toString())),
                "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1"))
                .isInstanceOf(com.nexa.api.notifications.application.exception.NotificationOperationException.class)
                .hasMessage("PUSH_DELIVERY_RETRYABLE");

        var attempt = org.mockito.ArgumentCaptor.forClass(PushSubscriptionPersistencePort.DeliveryAttempt.class);
        verify(persistence).recordAttempt(attempt.capture());
        assertThat(attempt.getValue().status()).isEqualTo("RETRYABLE");
        assertThat(attempt.getValue().providerCode()).isEqualTo("PROVIDER_EXCEPTION");
        assertThat(attempt.getValue().error()).isEqualTo("Provider delivery failed");
        assertThat(attempt.getValue().eventId()).isEqualTo(eventId.toString());
    }

    @Test
    void normalizesUnknownProviderStatusToFailedAttempt() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        UUID subscriptionId = UUID.randomUUID();
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "IOS", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        when(provider.deliver(any())).thenReturn(new PushProviderPort.DeliveryResult("UNKNOWN", "provider", "invalid result"));

        new PushRoutingService(persistence, provider).route(new NotificationProjection(
                UUID.randomUUID().toString(), TENANT.toString(), WORKSPACE.toString(), null, "SalesOrder", UUID.randomUUID().toString(),
                "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(), Set.of(MEMBERSHIP.toString())),
                "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1");

        var attempt = org.mockito.ArgumentCaptor.forClass(PushSubscriptionPersistencePort.DeliveryAttempt.class);
        verify(persistence).recordAttempt(attempt.capture());
        assertThat(attempt.getValue().status()).isEqualTo("FAILED");
    }

    private static CurrentAccessContext context() {
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        when(context.tenantId()).thenReturn(new TenantId(TENANT));
        when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE));
        when(context.membershipId()).thenReturn(new MembershipId(MEMBERSHIP));
        when(context.userId()).thenReturn(new UserId(USER));
        when(context.surface()).thenReturn(Surface.PLATFORM);
        return context;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
