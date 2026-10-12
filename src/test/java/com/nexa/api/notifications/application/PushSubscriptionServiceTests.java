package com.nexa.api.notifications.application;

import com.nexa.api.notifications.application.exception.NotificationOperationException;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.NotificationProjection;
import com.nexa.api.notifications.application.port.out.PushProviderPort;
import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import com.nexa.api.notifications.application.port.out.PushTransactionScopeGuard;
import com.nexa.api.notifications.application.service.PushRoutingService;
import com.nexa.api.notifications.application.service.PushSubscriptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        var subscription = new PushSubscriptionService(persistence).register(context(), "NATIVE", "install-1", "ios",
                "provider-secret-token", "push-register-1");
        assertThat(subscription.id()).isNotNull();
        assertThat(subscription.recipientMembershipId()).isEqualTo(MEMBERSHIP);

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
        claim(persistence, PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED);
        doThrow(new IllegalStateException("provider unavailable")).when(provider).deliver(any());

        assertThatThrownBy(() -> new PushRoutingService(persistence, provider).route(new NotificationProjection(
                eventId.toString(), TENANT.toString(), WORKSPACE.toString(), null, "SalesOrder", UUID.randomUUID().toString(),
                "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(), Set.of(MEMBERSHIP.toString())),
                "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1"))
                .isInstanceOf(com.nexa.api.notifications.application.exception.NotificationOperationException.class)
                .hasMessage("PUSH_DELIVERY_RETRYABLE");

        var delivery = org.mockito.ArgumentCaptor.forClass(PushProviderPort.Delivery.class);
        verify(provider).deliver(delivery.capture());
        assertThat(delivery.getValue().deliveryKey()).isEqualTo("push|" + eventId + "|" + subscriptionId);
        var attempt = org.mockito.ArgumentCaptor.forClass(PushSubscriptionPersistencePort.DeliveryAttempt.class);
        verify(persistence).completeDelivery(attempt.capture(), any(), anyInt(), anyInt(), anyBoolean(), nullable(Instant.class));
        assertThat(attempt.getValue().status()).isEqualTo("RETRYABLE");
        assertThat(attempt.getValue().providerCode()).isEqualTo("PROVIDER_EXCEPTION");
        assertThat(attempt.getValue().error()).isEqualTo("Provider delivery failed");
        assertThat(attempt.getValue().eventId()).isEqualTo(eventId.toString());
    }

    @Test
    void durableRoutingKeepsDeferredProviderWorkRetryable() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        UUID subscriptionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "ANDROID", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        claim(persistence, PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED);
        when(provider.deliver(any())).thenReturn(new PushProviderPort.DeliveryResult("DEFERRED", "PROVIDER_NOT_CONFIGURED", null));

        assertThatThrownBy(() -> new PushRoutingService(persistence, provider).routeDurable(new NotificationProjection(
                eventId.toString(), TENANT.toString(), WORKSPACE.toString(), null, "SalesOrder", UUID.randomUUID().toString(),
                "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(), Set.of(MEMBERSHIP.toString())),
                "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1"))
                .isInstanceOf(NotificationOperationException.class)
                .hasMessage("PUSH_DELIVERY_RETRYABLE");

        var attempt = org.mockito.ArgumentCaptor.forClass(PushSubscriptionPersistencePort.DeliveryAttempt.class);
        verify(persistence).completeDelivery(attempt.capture(), any(), anyInt(), anyInt(), anyBoolean(), nullable(Instant.class));
        assertThat(attempt.getValue().status()).isEqualTo("DEFERRED");
    }

    @Test
    void durableRetrySkipsSubscriptionsWithRecordedSuccess() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        UUID subscriptionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "ANDROID", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        claim(persistence, PushSubscriptionPersistencePort.DeliveryClaimStatus.ALREADY_SENT);

        new PushRoutingService(persistence, provider).routeDurable(new NotificationProjection(
                eventId.toString(), TENANT.toString(), WORKSPACE.toString(), null, "SalesOrder", UUID.randomUUID().toString(),
                "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(), Set.of(MEMBERSHIP.toString())),
                "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1");

        verify(provider, never()).deliver(any());
        verify(persistence, never()).completeDelivery(any(), any(), anyInt(), anyInt(), anyBoolean(), any());
    }

    @Test
    void terminalDeliveryClaimNeverCallsProviderAgain() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        UUID subscriptionId = UUID.randomUUID();
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "ANDROID", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        when(persistence.claimDelivery(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new PushSubscriptionPersistencePort.DeliveryClaim(
                        PushSubscriptionPersistencePort.DeliveryClaimStatus.DEAD_LETTER, null, 0));

        new PushRoutingService(persistence, provider).routeDurable(new NotificationProjection(
                UUID.randomUUID().toString(), TENANT.toString(), WORKSPACE.toString(), null,
                "SalesOrder", UUID.randomUUID().toString(), "SALES_ORDER_CONFIRMED", "CONFIRMED",
                Instant.now(), Set.of(MEMBERSHIP.toString())), "ORDER_STATUS", "Order confirmed",
                "Order confirmed.", "/sales-orders/1");

        verify(provider, never()).deliver(any());
        verify(persistence, never()).completeDelivery(any(), any(), anyInt(), anyInt(), anyBoolean(), any());
    }

    @Test
    void reachingConfiguredAttemptLimitDeadLettersAndPreventsAnotherProviderCall() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        UUID subscriptionId = UUID.randomUUID();
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "ANDROID", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        UUID token = UUID.randomUUID();
        when(persistence.claimDelivery(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new PushSubscriptionPersistencePort.DeliveryClaim(
                        PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED, token, 20))
                .thenReturn(new PushSubscriptionPersistencePort.DeliveryClaim(
                        PushSubscriptionPersistencePort.DeliveryClaimStatus.DEAD_LETTER, null, 0));
        when(persistence.completeDelivery(any(), any(), anyInt(), anyInt(), anyBoolean(), nullable(Instant.class)))
                .thenReturn(PushSubscriptionPersistencePort.DeliveryCompletion.DEAD_LETTER);
        when(provider.deliver(any())).thenReturn(new PushProviderPort.DeliveryResult("DEFERRED", "offline", null));
        Clock clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), java.time.ZoneOffset.UTC);
        var policy = new com.nexa.api.notifications.application.service.PushDeliveryRetryPolicy(
                20, 300, clock, () -> 0.0d);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        PushTransactionScopeGuard scopeGuard = mock(PushTransactionScopeGuard.class);
        var routing = new PushRoutingService(persistence, provider, transactions, policy, scopeGuard, TENANT, WORKSPACE);
        var event = new NotificationProjection(UUID.randomUUID().toString(), TENANT.toString(), WORKSPACE.toString(), null,
                "SalesOrder", UUID.randomUUID().toString(), "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.EPOCH,
                Set.of(MEMBERSHIP.toString()));

        routing.routeDurable(event, "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1");
        routing.routeDurable(event, "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1");

        verify(provider).deliver(any());
        verify(persistence).completeDelivery(any(), any(), org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(20), org.mockito.ArgumentMatchers.eq(true), any());
    }

    @Test
    void tenantPushPersistsOnlyUnderVerifiedScopeAndRunsProviderWithoutTransaction() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID subscriptionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForMap(any(String.class))).thenReturn(Map.of(
                "tenant_id", TENANT.toString(), "workspace_id", WORKSPACE.toString()));
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "ANDROID", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        claim(persistence, PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED);
        when(provider.deliver(any())).thenReturn(new PushProviderPort.DeliveryResult("SENT", "accepted", null));

        new PushRoutingService(persistence, provider, transactions, new com.nexa.api.notifications.infrastructure.persistence.JdbcPushTransactionScopeGuard(jdbc), TENANT, WORKSPACE).routeDurable(
                new NotificationProjection(eventId.toString(), TENANT.toString(), WORKSPACE.toString(), null,
                        "Delivery", UUID.randomUUID().toString(), "DeliveryCompleted.v1", null, Instant.now(),
                        Set.of(MEMBERSHIP.toString())), "ORDER_STATUS", "Delivery complete",
                "Delivery complete was recorded.", null);

        var definitions = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, org.mockito.Mockito.times(3)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).extracting(TransactionDefinition::getPropagationBehavior)
                .containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                        TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(jdbc, org.mockito.Mockito.times(3)).queryForMap(any(String.class));
        verify(provider).deliver(any());
    }

    @Test
    void tenantPushFailsClosedWhenTransactionTenantScopeDoesNotMatchBinding() {
        assertScopeMismatchStopsRouting(UUID.randomUUID().toString(), WORKSPACE.toString());
    }

    @Test
    void tenantPushFailsClosedWhenTransactionWorkspaceScopeDoesNotMatchBinding() {
        assertScopeMismatchStopsRouting(TENANT.toString(), UUID.randomUUID().toString());
    }

    @Test
    void truncatesProviderErrorToTheDurableColumnLimit() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        UUID subscriptionId = UUID.randomUUID();
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "IOS", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        claim(persistence, PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED);
        String longError = "x".repeat(2_001);
        when(provider.deliver(any())).thenReturn(new PushProviderPort.DeliveryResult("FAILED", "provider", longError));

        new PushRoutingService(persistence, provider).route(new NotificationProjection(
                UUID.randomUUID().toString(), TENANT.toString(), WORKSPACE.toString(), null, "SalesOrder", UUID.randomUUID().toString(),
                "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(), Set.of(MEMBERSHIP.toString())),
                "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1");

        var attempt = org.mockito.ArgumentCaptor.forClass(PushSubscriptionPersistencePort.DeliveryAttempt.class);
        verify(persistence).completeDelivery(attempt.capture(), any(), anyInt(), anyInt(), anyBoolean(), nullable(Instant.class));
        assertThat(attempt.getValue().error()).hasSize(2_000);
    }

    @Test
    void normalizesUnknownProviderStatusToFailedAttempt() {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        UUID subscriptionId = UUID.randomUUID();
        when(persistence.activeForRecipient(TENANT, WORKSPACE, MEMBERSHIP)).thenReturn(List.of(
                new PushSubscriptionPersistencePort.PushSubscription(subscriptionId, MEMBERSHIP, "install-1",
                        "IOS", "PLATFORM", "ENABLED", Instant.EPOCH, Instant.EPOCH, 0)));
        claim(persistence, PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED);
        when(provider.deliver(any())).thenReturn(new PushProviderPort.DeliveryResult("UNKNOWN", "provider", "invalid result"));

        new PushRoutingService(persistence, provider).route(new NotificationProjection(
                UUID.randomUUID().toString(), TENANT.toString(), WORKSPACE.toString(), null, "SalesOrder", UUID.randomUUID().toString(),
                "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(), Set.of(MEMBERSHIP.toString())),
                "ORDER_STATUS", "Order confirmed", "Order confirmed.", "/sales-orders/1");

        var attempt = org.mockito.ArgumentCaptor.forClass(PushSubscriptionPersistencePort.DeliveryAttempt.class);
        verify(persistence).completeDelivery(attempt.capture(), any(), anyInt(), anyInt(), anyBoolean(), nullable(Instant.class));
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

    private static void claim(PushSubscriptionPersistencePort persistence,
                              PushSubscriptionPersistencePort.DeliveryClaimStatus status) {
        UUID token = status == PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED ? UUID.randomUUID() : null;
        when(persistence.claimDelivery(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new PushSubscriptionPersistencePort.DeliveryClaim(status, token,
                        status == PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED ? 1 : 0));
        when(persistence.completeDelivery(any(), any(), anyInt(), anyInt(), anyBoolean(), nullable(Instant.class)))
                .thenAnswer(invocation -> {
                    var attempt = invocation.getArgument(0, PushSubscriptionPersistencePort.DeliveryAttempt.class);
                    if ("SENT".equals(attempt.status())) return PushSubscriptionPersistencePort.DeliveryCompletion.SENT;
                    if (invocation.getArgument(4, Boolean.class)) {
                        return PushSubscriptionPersistencePort.DeliveryCompletion.RETRY_SCHEDULED;
                    }
                    return PushSubscriptionPersistencePort.DeliveryCompletion.DEAD_LETTER;
                });
    }

    private static void assertScopeMismatchStopsRouting(String actualTenantId, String actualWorkspaceId) {
        PushSubscriptionPersistencePort persistence = mock(PushSubscriptionPersistencePort.class);
        PushProviderPort provider = mock(PushProviderPort.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(any(String.class))).thenReturn(Map.of(
                "tenant_id", actualTenantId, "workspace_id", actualWorkspaceId));

        PushRoutingService routing = new PushRoutingService(persistence, provider, transactions,
                new com.nexa.api.notifications.infrastructure.persistence.JdbcPushTransactionScopeGuard(jdbc),
                TENANT, WORKSPACE);

        assertThatThrownBy(() -> routing.routeDurable(new NotificationProjection(
                UUID.randomUUID().toString(), TENANT.toString(), WORKSPACE.toString(), null,
                "Delivery", UUID.randomUUID().toString(), "DeliveryCompleted.v1", null, Instant.now(),
                Set.of(MEMBERSHIP.toString())), "ORDER_STATUS", "Delivery complete",
                "Delivery complete was recorded.", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tenant push transaction lost its verified Tenant/Workspace RLS scope");

        verify(jdbc).queryForMap(any(String.class));
        org.mockito.Mockito.verifyNoInteractions(persistence, provider);
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
