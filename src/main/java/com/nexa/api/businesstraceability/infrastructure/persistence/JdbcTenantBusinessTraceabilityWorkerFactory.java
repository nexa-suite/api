package com.nexa.api.businesstraceability.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.businesstraceability.application.service.SafeAuditMetadata;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Tenant-side BC-11 projector; each fact and its inbox receipt commit in the caller's Tenant transaction. */
@Component
@Profile("!test")
public final class JdbcTenantBusinessTraceabilityWorkerFactory implements TenantBusinessTraceabilityWorkerFactory {
    private static final String CONSUMER = "business-traceability-v1";
    private static final int MAX_BATCH = 50;
    private static final String TRACE_EVENT = "BusinessFactTraced.v1";
    private static final String PUSH_WORK_EVENT = "NOTIFICATION_PUSH_DELIVERY_REQUESTED";
    private final ObjectMapper mapper;

    public JdbcTenantBusinessTraceabilityWorkerFactory(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "Traceability payload mapper is required");
    }

    @Override
    public Worker bindTo(JdbcTemplate tenantJdbc, BusinessTraceabilityCommands tenantTraceability) {
        return new JdbcWorker(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(tenantTraceability, "Tenant traceability commands are required"), mapper);
    }

    private static final class JdbcWorker implements Worker {
        private final JdbcTemplate jdbc;
        private final BusinessTraceabilityCommands traceability;
        private final ObjectMapper mapper;

        private JdbcWorker(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability, ObjectMapper mapper) {
            this.jdbc = jdbc;
            this.traceability = traceability;
            this.mapper = mapper;
        }

        @Override
        public int projectPendingBusinessFacts(UUID tenantId, UUID workspaceId, int limit) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Tenant business-traceability worker must join an active Tenant transaction");
            }
            List<TenantOutboxEvent> events = findPendingBusinessFacts(tenantId, workspaceId, limit, null, null);
            for (TenantOutboxEvent event : events) project(tenantId, workspaceId, event);
            return events.size();
        }

        @Override
        public List<TenantOutboxEvent> findPendingBusinessFacts(UUID tenantId, UUID workspaceId, int limit,
                Instant afterCreatedAt, UUID afterEventId) {
            requireTransaction();
            requireScope(tenantId, workspaceId);
            requireLimit(limit, "Traceability batch size");
            requireCursor(afterCreatedAt, afterEventId);
            String cursor = afterCreatedAt == null ? "" : " and (event.created_at,event.event_id) > (?,?)";
            List<Object> arguments = new java.util.ArrayList<>(List.of(tenantId, workspaceId, TRACE_EVENT,
                    PUSH_WORK_EVENT, CONSUMER));
            if (afterCreatedAt != null) {
                arguments.add(Timestamp.from(afterCreatedAt));
                arguments.add(afterEventId);
            }
            arguments.add(Math.min(MAX_BATCH, limit));
            return jdbc.query("""
                    select event.event_id,event.event_type,event.aggregate_type,event.aggregate_id,
                           event.tenant_id,event.workspace_id,event.occurred_at,event.correlation_id,
                           event.causation_id,event.schema_version,event.payload::text,event.created_at
                      from integration.outbox_event event
                     where event.tenant_id=? and event.workspace_id=?
                       and event.event_type not in (?,?)
                       and not exists (
                           select 1 from integration.inbox_event inbox
                            where inbox.consumer_name=? and inbox.event_id=event.event_id
                              and inbox.tenant_id=event.tenant_id and inbox.workspace_id=event.workspace_id)
                    """ + cursor + " order by event.created_at,event.event_id limit ? for update of event skip locked",
                    JdbcWorker::event, arguments.toArray());
        }

        @Override
        public boolean projectBusinessFact(TenantOutboxEvent expected) {
            requireTransaction();
            TenantOutboxEvent candidate = lockExpected(Objects.requireNonNull(expected));
            if (candidate == null || hasReceipt(CONSUMER, candidate)) return false;
            project(candidate.tenantId(), candidate.workspaceId(), candidate);
            return true;
        }

        @Override
        public List<TenantOutboxEvent> findPendingForConsumer(UUID tenantId, UUID workspaceId,
                String consumerName, Set<String> eventTypes, int limit) {
            return findPendingForConsumer(tenantId, workspaceId, consumerName, eventTypes, limit, null, null);
        }

        @Override
        public List<TenantOutboxEvent> findPendingForConsumer(UUID tenantId, UUID workspaceId,
                String consumerName, Set<String> eventTypes, int limit, Instant afterCreatedAt, UUID afterEventId) {
            requireTransaction();
            requireScope(tenantId, workspaceId);
            String consumer = requireConsumer(consumerName);
            Set<String> types = Set.copyOf(Objects.requireNonNull(eventTypes, "Consumer event types are required"));
            if (types.isEmpty()) return List.of();
            if (types.stream().anyMatch(type -> type == null || type.isBlank())) {
                throw new IllegalArgumentException("Consumer event types must be nonblank");
            }
            requireLimit(limit, "Tenant outbox consumer batch size");
            requireCursor(afterCreatedAt, afterEventId);
            List<String> orderedTypes = types.stream().sorted().toList();
            String placeholders = String.join(",", java.util.Collections.nCopies(orderedTypes.size(), "?"));
            List<Object> arguments = new java.util.ArrayList<>();
            arguments.add(tenantId);
            arguments.add(workspaceId);
            arguments.addAll(orderedTypes);
            arguments.add(consumer);
            String cursor = afterCreatedAt == null ? "" : " and (event.created_at,event.event_id) > (?,?)";
            if (afterCreatedAt != null) {
                arguments.add(Timestamp.from(afterCreatedAt));
                arguments.add(afterEventId);
            }
            arguments.add(Math.min(MAX_BATCH, limit));
            return jdbc.query("""
                    select event.event_id,event.event_type,event.aggregate_type,event.aggregate_id,
                           event.tenant_id,event.workspace_id,event.occurred_at,event.correlation_id,
                           event.causation_id,event.schema_version,event.payload::text,event.created_at
                      from integration.outbox_event event
                     where event.tenant_id=? and event.workspace_id=?
                       and event.event_type in (%s)
                       and not exists (
                           select 1 from integration.inbox_event inbox
                            where inbox.consumer_name=? and inbox.event_id=event.event_id
                              and inbox.tenant_id=event.tenant_id and inbox.workspace_id=event.workspace_id)
                    """.formatted(placeholders) + cursor
                    + " order by event.created_at,event.event_id limit ? for update of event skip locked",
                    JdbcWorker::event, arguments.toArray());
        }

        @Override
        public <T> Optional<T> inspectPendingEvent(TenantOutboxEvent expected, String consumerName,
                EventRead<T> read) {
            requireTransaction();
            TenantOutboxEvent candidate = lockExpected(Objects.requireNonNull(expected));
            if (candidate == null || hasReceipt(requireConsumer(consumerName), candidate)) return Optional.empty();
            return Optional.ofNullable(Objects.requireNonNull(read, "Tenant outbox read callback is required")
                    .read(jdbc, candidate));
        }

        @Override
        public boolean consumePendingEvent(TenantOutboxEvent expected, String consumerName,
                org.springframework.transaction.PlatformTransactionManager tenantTransactionManager,
                ConsumerWork work) {
            requireTransaction();
            String consumer = requireConsumer(consumerName);
            TenantOutboxEvent candidate = lockExpected(Objects.requireNonNull(expected));
            if (candidate == null || hasReceipt(consumer, candidate)) return false;
            Objects.requireNonNull(work, "Tenant outbox consumer callback is required")
                    .consume(jdbc, Objects.requireNonNull(tenantTransactionManager,
                            "Tenant transaction manager is required"), candidate);
            jdbc.update("insert into integration.inbox_event(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) "
                            + "values (?,?,?,?,?,'PROCESSED') on conflict (consumer_name,event_id) do nothing",
                    consumer, candidate.eventId(), candidate.tenantId(), candidate.workspaceId(), Timestamp.from(Instant.now()));
            return true;
        }

        private void project(UUID tenantId, UUID workspaceId, TenantOutboxEvent event) {
            if (!tenantId.equals(event.tenantId()) || !workspaceId.equals(event.workspaceId())) {
                throw new IllegalStateException("Tenant outbox row escaped its verified workspace scope");
            }
            Map<String, Object> payload = payload(event.payload());
            UUID actorMembershipId = optionalUuid(payload.get("actorMembershipId"));
            Object rawWorkArea = payload.get("actorWorkArea");
            String actorWorkArea = rawWorkArea instanceof String value && !value.isBlank() ? value : "SYSTEM";
            traceability.record(new BusinessTraceabilityCommands.TraceRequest(event.tenantId(), event.workspaceId(),
                    actorMembershipId, actorWorkArea, event.eventType(), event.aggregateType(), event.aggregateId(),
                    event.correlationId(), event.eventId().toString(), SafeAuditMetadata.sanitize(payload),
                    event.occurredAt()));
            jdbc.update("insert into integration.inbox_event(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) "
                            + "values (?,?,?,?,?,'PROCESSED') on conflict (consumer_name,event_id) do nothing",
                    CONSUMER, event.eventId(), event.tenantId(), event.workspaceId(), Timestamp.from(Instant.now()));
        }

        private Map<String, Object> payload(String json) {
            try {
                return mapper.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() { });
            } catch (Exception exception) {
                throw new IllegalArgumentException("Tenant source event payload is invalid", exception);
            }
        }

        private static TenantOutboxEvent event(ResultSet rs, int row) throws SQLException {
            return new TenantOutboxEvent(rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                    rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                    rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                    rs.getTimestamp("occurred_at").toInstant(), rs.getString("correlation_id"),
                    rs.getObject("causation_id", UUID.class), rs.getString("schema_version"),
                    rs.getString("payload"), rs.getTimestamp("created_at").toInstant());
        }

        private TenantOutboxEvent lockExpected(TenantOutboxEvent expected) {
            requireScope(expected.tenantId(), expected.workspaceId());
            List<TenantOutboxEvent> rows = jdbc.query("""
                    select event_id,event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,
                           correlation_id,causation_id,schema_version,payload::text,created_at
                      from integration.outbox_event
                     where event_id=? and tenant_id=? and workspace_id=?
                     for update skip locked
                    """, JdbcWorker::event, expected.eventId(), expected.tenantId(), expected.workspaceId());
            if (rows.isEmpty()) return null;
            TenantOutboxEvent actual = rows.getFirst();
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Tenant outbox event changed after its verified short read");
            }
            return actual;
        }

        private boolean hasReceipt(String consumer, TenantOutboxEvent event) {
            Boolean exists = jdbc.queryForObject("select exists(select 1 from integration.inbox_event "
                            + "where consumer_name=? and event_id=? and tenant_id=? and workspace_id=?)",
                    Boolean.class, consumer, event.eventId(), event.tenantId(), event.workspaceId());
            return Boolean.TRUE.equals(exists);
        }

        private void requireTransaction() {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Tenant outbox consumer must join an active Tenant transaction");
            }
        }

        private static void requireLimit(int limit, String description) {
            if (limit < 1 || limit > MAX_BATCH) {
                throw new IllegalArgumentException(description + " must be between 1 and 50");
            }
        }

        private static void requireCursor(Instant afterCreatedAt, UUID afterEventId) {
            if ((afterCreatedAt == null) != (afterEventId == null)) {
                throw new IllegalArgumentException("Tenant outbox keyset cursor must include both creation time and event id");
            }
        }

        private static void requireScope(UUID tenantId, UUID workspaceId) {
            Objects.requireNonNull(tenantId, "Tenant scope is required");
            Objects.requireNonNull(workspaceId, "Workspace scope is required");
        }

        private static String requireConsumer(String consumerName) {
            if (consumerName == null || consumerName.isBlank() || consumerName.length() > 120) {
                throw new IllegalArgumentException("Tenant outbox consumer name is invalid");
            }
            return consumerName.strip();
        }

        private static UUID optionalUuid(Object value) {
            if (value == null || String.valueOf(value).isBlank()) return null;
            return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
        }
    }

}
