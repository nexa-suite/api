package com.nexa.api.businesstraceability.application.publicapi;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * BC-11 write boundary for critical business facts. The implementation uses
 * the existing append-only audit store and canonical outbox; it is not a
 * second audit subsystem.
 */
public interface BusinessTraceabilityCommands {
    void record(TraceRequest request);

    record TraceRequest(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                        String actorWorkArea, String eventType, String subjectType,
                        UUID subjectId, String correlationId, String occurrenceKey,
                        Map<String, Object> metadata, Instant occurredAt) {
        public TraceRequest {
            if (tenantId == null || workspaceId == null || eventType == null || eventType.isBlank()
                    || subjectType == null || subjectType.isBlank() || subjectId == null
                    || occurrenceKey == null || occurrenceKey.isBlank() || occurredAt == null) {
                throw new IllegalArgumentException("Traceability request is incomplete");
            }
            actorWorkArea = actorWorkArea == null || actorWorkArea.isBlank()
                    ? "SYSTEM" : actorWorkArea.trim();
            eventType = eventType.trim();
            subjectType = subjectType.trim();
            correlationId = correlationId == null || correlationId.isBlank()
                    ? occurrenceKey.trim() : correlationId.trim();
            occurrenceKey = occurrenceKey.trim();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
