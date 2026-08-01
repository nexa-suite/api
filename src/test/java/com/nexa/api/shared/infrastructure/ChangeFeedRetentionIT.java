package com.nexa.api.shared.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class ChangeFeedRetentionIT extends PostgresIntegrationSupport {
    @Test void expiredEventsAreRemovedOnlyByTheMaintenanceFunction() {
        UUID tenant = UUID.fromString(tenantId()); UUID workspace = UUID.fromString(workspaceId());
        jdbc.update("insert into integration.change_event(event_id,tenant_id,workspace_id,aggregate_type,aggregate_id,event_type,audiences,occurred_at,expires_at) values (?,?,?,?,?,?,?::text[],?,?)", UUID.randomUUID(), tenant, workspace, "test", UUID.randomUUID(), "test.expired", "{OWNER}", Timestamp.from(Instant.now().minusSeconds(20)), Timestamp.from(Instant.now().minusSeconds(1)));
        Long deleted = jdbc.queryForObject("select integration.purge_expired_change_events(?)", Long.class, 1000);
        assertThat(deleted).isGreaterThanOrEqualTo(1);
    }
}
