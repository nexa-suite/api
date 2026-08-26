package com.nexa.api.businesstraceability.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Existing audit.event + outbox implementation of the BC-11 write port. */
@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcBusinessTraceabilityAdapter implements BusinessTraceabilityCommands {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcBusinessTraceabilityAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(TraceRequest request) {
        UUID auditId = UUID.nameUUIDFromBytes((request.tenantId() + "|" + request.workspaceId()
                + "|" + request.eventType() + "|" + request.subjectType() + "|"
                + request.subjectId() + "|" + request.occurrenceKey()).getBytes(StandardCharsets.UTF_8));
        try {
            jdbc.update("insert into audit.event(id,tenant_id,workspace_id,actor_membership_id,actor_work_area,event_type,subject_type,subject_id,correlation_id,safe_metadata,occurred_at) values (?,?,?,?,?,?,?,?,?,?::jsonb,?) on conflict (id) do nothing",
                    auditId, request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                    bounded(request.actorWorkArea(), 32), bounded(request.eventType(), 120),
                    bounded(request.subjectType(), 120), request.subjectId(),
                    bounded(request.correlationId(), 120), mapper.writeValueAsString(request.metadata()),
                    Timestamp.from(request.occurredAt()));
        } catch (Exception exception) {
            throw new IllegalStateException("Business traceability could not be persisted", exception);
        }
        CanonicalOutbox.append(jdbc, "BusinessFactTraced.v1", "BusinessTraceabilityRecord", auditId,
                request.tenantId(), request.workspaceId(), request.occurredAt(), request.correlationId(),
                null, "1.0", request.occurrenceKey(), Map.of(
                        "traceId", auditId,
                        "eventType", request.eventType(),
                        "subjectType", request.subjectType(),
                        "subjectId", request.subjectId(),
                        "metadata", request.metadata()));
    }

    private static String bounded(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
