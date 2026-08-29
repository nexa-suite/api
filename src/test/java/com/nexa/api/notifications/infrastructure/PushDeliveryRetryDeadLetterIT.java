package com.nexa.api.notifications.infrastructure;

import com.nexa.api.shared.infrastructure.events.CanonicalOutboxEventProcessor;
import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL proof that deferred push remains in canonical retry/dead-letter flow. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PushDeliveryRetryDeadLetterIT extends NexaWorkflowIntegrationSupport {
    @Autowired
    private CanonicalOutboxEventProcessor processor;

    private UUID subscriptionId;
    private UUID outboxEventId;

    @AfterEach
    void cleanPushWorkItem() {
        if (outboxEventId != null) {
            jdbc.update("delete from integration.outbox_event where event_id=?", outboxEventId);
        }
        if (subscriptionId != null) {
            jdbc.update("update notifications.push_subscription set status='DISABLED' where id=?", subscriptionId);
        }
    }

    @Test
    void deferredProviderUsesCanonicalRetryAndDeadLetterAtLimit() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID membership = UUID.fromString(membershipId(BUYER_EMAIL));
        UUID user = jdbc.queryForObject("select user_id from tenant_management.workspace_membership where id=?", UUID.class, membership);
        subscriptionId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update("insert into notifications.push_subscription(id,tenant_id,workspace_id,recipient_membership_id,user_id,surface,installation_id,platform,provider_token_hash,status,created_at,updated_at,last_seen_at,version) values (?,?,?,?,?,'PORTAL',?,'IOS',?,'ENABLED',?,?,?,0)",
                subscriptionId, tenant, workspace, membership, user, "push-retry-" + subscriptionId,
                "a".repeat(64), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));

        String payload = "{\"sourceEventId\":\"" + sourceEventId + "\",\"tenantId\":\"" + tenant
                + "\",\"workspaceId\":\"" + workspace + "\",\"clientAccountId\":null"
                + ",\"aggregateType\":\"SalesOrder\",\"aggregateId\":\"" + aggregateId
                + "\",\"eventType\":\"SALES_ORDER_CONFIRMED\",\"publicStatus\":\"CONFIRMED\""
                + ",\"occurredAt\":\"" + now + "\",\"recipientMembershipIds\":[\"" + membership
                + "\"],\"category\":\"ORDER_STATUS\",\"title\":\"Order confirmed\""
                + ",\"message\":\"Safe notification\",\"deepLink\":\"/sales-orders/" + aggregateId + "\"}";
        jdbc.update("insert into integration.outbox_event(event_id,event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,correlation_id,schema_version,payload,status,attempt_count,next_attempt_at,processing_started_at,lease_until,claim_token,created_at) values (?,?,?,?,?,?,?,?,'1.0',?::jsonb,'PROCESSING',19,current_timestamp,current_timestamp,current_timestamp - interval '1 second',?,current_timestamp - interval '1 hour')",
                outboxEventId, "NOTIFICATION_PUSH_DELIVERY_REQUESTED", "NotificationPushDelivery", aggregateId,
                tenant, workspace, Timestamp.from(now), "push-retry-dead-letter-" + outboxEventId, payload, UUID.randomUUID());

        processor.processBatch();

        assertThat(jdbc.queryForObject("select status from integration.outbox_event where event_id=?", String.class, outboxEventId))
                .isEqualTo("DEAD_LETTER");
        assertThat(jdbc.queryForObject("select count(*) from notifications.push_delivery_attempt where subscription_id=? and event_id=?",
                Integer.class, subscriptionId, sourceEventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from notifications.push_delivery_attempt where subscription_id=? and event_id=?",
                String.class, subscriptionId, sourceEventId)).isEqualTo("DEFERRED");
        assertThat(jdbc.queryForObject("select count(*) from notifications.push_delivery_claim where subscription_id=? and event_id=? and status='CLAIMED' and claim_token is null and lease_until <= current_timestamp",
                Integer.class, subscriptionId, sourceEventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from integration.inbox_event where consumer_name=? and event_id=?",
                Integer.class, "nexa-service-foundation-v1-push", outboxEventId)).isZero();
    }
}
