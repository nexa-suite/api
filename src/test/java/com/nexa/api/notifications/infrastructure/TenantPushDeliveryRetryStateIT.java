package com.nexa.api.notifications.infrastructure;

import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import com.nexa.api.notifications.infrastructure.persistence.JdbcPushSubscriptionAdapter;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises Tenant-scoped push claim fencing, retry scheduling and terminal state on PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantPushDeliveryRetryStateIT extends NexaWorkflowIntegrationSupport {
    @Autowired
    private TenantBusinessTraceabilityWorkerRouter workerRouter;

    @Test
    void deliveryClaimSerializesWorkersFencesStaleOwnerSchedulesDueRetryAndDeadLettersAtLimit() throws Exception {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID membership = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID user = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?",
                UUID.class, membership);
        UUID subscription = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        Instant now = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        JdbcTemplate tenantAdmin = tenantJdbcFor(tenant);
        tenantAdmin.update("insert into notifications.push_subscription "
                        + "(id,tenant_id,workspace_id,recipient_membership_id,user_id,surface,installation_id,platform,provider_token_hash,status,created_at,updated_at,last_seen_at,version) "
                        + "values (?,?,?,?,?,'PORTAL',?,'IOS',?,'ENABLED',?,?,?,0)",
                subscription, tenant, workspace, membership, user, "retry-state-" + subscription,
                "a".repeat(64), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));

        try {
            var pool = Executors.newFixedThreadPool(2);
            try {
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                var first = pool.submit(() -> concurrentClaim(tenant, workspace, subscription, event, now, ready, start));
                var second = pool.submit(() -> concurrentClaim(tenant, workspace, subscription, event, now, ready, start));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                var claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertThat(claims).extracting(PushSubscriptionPersistencePort.DeliveryClaim::status)
                        .containsExactlyInAnyOrder(PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED,
                                PushSubscriptionPersistencePort.DeliveryClaimStatus.BUSY);
                var original = claims.stream().filter(claim -> claim.status()
                        == PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED).findFirst().orElseThrow();

                Instant expiredAt = now.plus(Duration.ofMinutes(11));
                tenantAdmin.update("update notifications.push_delivery_claim set lease_until=? "
                                + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=?",
                        Timestamp.from(now.minusSeconds(1)), tenant, workspace, subscription, event);
                var expiredAttempt = attempt(tenant, workspace, subscription, event, expiredAt, "RETRYABLE");
                assertThatThrownBy(() -> complete(tenant, workspace, expiredAttempt, original.claimToken(), 1, 2,
                        true, expiredAt.plusSeconds(1)))
                        .hasMessageContaining("PUSH_DELIVERY_CLAIM_LOST");
                assertThat(tenantAdmin.queryForObject("select count(*) from notifications.push_delivery_attempt "
                                + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=?",
                        Integer.class, tenant, workspace, subscription, event)).isZero();

                var replacement = claim(tenant, workspace, subscription, event, expiredAt, 2);
                assertThat(replacement.status()).isEqualTo(PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED);
                assertThat(replacement.attemptNumber()).isEqualTo(1);
                var staleAttempt = attempt(tenant, workspace, subscription, event, expiredAt, "RETRYABLE");
                assertThatThrownBy(() -> complete(tenant, workspace, staleAttempt, original.claimToken(), 1, 2,
                        true, expiredAt.plusSeconds(1)))
                        .hasMessageContaining("PUSH_DELIVERY_CLAIM_LOST");

                Instant retryAt = expiredAt.plusSeconds(5);
                assertThat(complete(tenant, workspace, attempt(tenant, workspace, subscription, event, expiredAt,
                        "RETRYABLE"), replacement.claimToken(), 1, 2, true, retryAt))
                        .isEqualTo(PushSubscriptionPersistencePort.DeliveryCompletion.RETRY_SCHEDULED);
                assertThat(claim(tenant, workspace, subscription, event, retryAt.minusMillis(1), 2).status())
                        .isEqualTo(PushSubscriptionPersistencePort.DeliveryClaimStatus.BUSY);
                var due = claim(tenant, workspace, subscription, event, retryAt, 2);
                assertThat(due.status()).isEqualTo(PushSubscriptionPersistencePort.DeliveryClaimStatus.CLAIMED);
                assertThat(due.attemptNumber()).isEqualTo(2);
                assertThat(complete(tenant, workspace, attempt(tenant, workspace, subscription, event, retryAt,
                        "RETRYABLE"), due.claimToken(), 2, 2, true, retryAt.plusSeconds(1)))
                        .isEqualTo(PushSubscriptionPersistencePort.DeliveryCompletion.DEAD_LETTER);
                assertThat(claim(tenant, workspace, subscription, event, retryAt.plusSeconds(2), 2).status())
                        .isEqualTo(PushSubscriptionPersistencePort.DeliveryClaimStatus.DEAD_LETTER);
            } finally {
                pool.shutdownNow();
            }

            assertThat(tenantAdmin.queryForObject("select count(*) from notifications.push_delivery_attempt "
                            + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=?",
                    Integer.class, tenant, workspace, subscription, event)).isEqualTo(2);
            assertThat(tenantAdmin.queryForObject("select status from notifications.push_delivery_claim "
                            + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=?",
                    String.class, tenant, workspace, subscription, event)).isEqualTo("DEAD_LETTER");
        } finally {
            // Keep append-only delivery evidence; random test identities isolate these rows from later runs.
            tenantAdmin.update("update notifications.push_subscription set status='DISABLED',updated_at=clock_timestamp() "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    tenant, workspace, subscription);
        }
    }

    private PushSubscriptionPersistencePort.DeliveryClaim concurrentClaim(UUID tenant, UUID workspace,
            UUID subscription, UUID event, Instant now, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Claim race was not released");
        return claim(tenant, workspace, subscription, event, now, 2);
    }

    private PushSubscriptionPersistencePort.DeliveryClaim claim(UUID tenant, UUID workspace, UUID subscription,
            UUID event, Instant now, int maxAttempts) {
        return workerRouter.inTransaction(tenant, workspace, (tenantJdbc, ignored) ->
                new JdbcPushSubscriptionAdapter(tenantJdbc).claimDelivery(tenant, workspace, subscription,
                        event.toString(), "push|" + event + "|" + subscription, maxAttempts, now,
                        now.plus(Duration.ofMinutes(10))));
    }

    private PushSubscriptionPersistencePort.DeliveryCompletion complete(UUID tenant, UUID workspace,
            PushSubscriptionPersistencePort.DeliveryAttempt attempt, UUID token, int attemptNumber,
            int maxAttempts, boolean retryable, Instant retryAt) {
        return workerRouter.inTransaction(tenant, workspace, (tenantJdbc, ignored) ->
                new JdbcPushSubscriptionAdapter(tenantJdbc).completeDelivery(attempt, token, attemptNumber,
                        maxAttempts, retryable, retryAt));
    }

    private static PushSubscriptionPersistencePort.DeliveryAttempt attempt(UUID tenant, UUID workspace,
            UUID subscription, UUID event, Instant now, String status) {
        return new PushSubscriptionPersistencePort.DeliveryAttempt(tenant, workspace, subscription,
                event.toString(), "SALES_ORDER_CONFIRMED", status, "PROVIDER_UNAVAILABLE", null, now);
    }
}
