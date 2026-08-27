package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class CanonicalOutboxDeadLetterIT extends NexaWorkflowIntegrationSupport {
    @Autowired
    private CanonicalOutboxEventProcessor processor;

    @Test
    void expiredClaimAtMaximumAttemptBecomesDeadLetter() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("insert into integration.outbox_event(event_id,event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,correlation_id,schema_version,payload,status,attempt_count,next_attempt_at,processing_started_at,lease_until,claim_token) values (?,?,?,?,?,?,current_timestamp,?,'v1','{}'::jsonb,'PROCESSING',20,current_timestamp,current_timestamp,? ,?)",
                eventId, "DEAD_LETTER_TEST", "DeadLetterTest", UUID.randomUUID(), UUID.fromString(tenantId()), UUID.fromString(workspaceId()),
                "dead-letter-test", Timestamp.from(Instant.now().minusSeconds(60)), UUID.randomUUID());
        try {
            processor.processBatch();
            assertThat(jdbc.queryForObject("select status from integration.outbox_event where event_id=?", String.class, eventId)).isEqualTo("DEAD_LETTER");
        } finally {
            jdbc.update("delete from integration.outbox_event where event_id=?", eventId);
        }
    }
}
