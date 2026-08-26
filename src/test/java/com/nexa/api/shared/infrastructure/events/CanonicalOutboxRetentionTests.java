package com.nexa.api.shared.infrastructure.events;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalOutboxRetentionTests {
    @Test
    void retentionRedactsOnlyPublishedPayloadsInBoundedBatches() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/nexa/api/shared/infrastructure/events/CanonicalOutboxEventProcessor.java"));

        assertThat(source).contains("status='PUBLISHED'", "payload='{}'::jsonb", "limit ?",
                "processed_at", "outboxRetentionBatchSize");
        assertThat(source).doesNotContain("delete from integration.outbox_event");
    }
}
