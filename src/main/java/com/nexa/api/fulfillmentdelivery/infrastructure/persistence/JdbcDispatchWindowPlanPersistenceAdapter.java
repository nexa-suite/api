package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchWindowPlanModels.View;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchWindowPlanPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchWindowPlanPersistencePort.RecordCommand;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC adapter for immutable Dispatch window plans. */
@Repository
@Profile("!test")
public class JdbcDispatchWindowPlanPersistenceAdapter implements DispatchWindowPlanPersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcDispatchWindowPlanPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<View> findReplay(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                                    String idempotencyKey, String requestHash) {
        List<StoredPlan> values = jdbc.query("select fulfillment_id,fulfillment_version,revision,window_start,window_end,"
                        + "reason,actor_membership_id,recorded_at,request_hash from logistics.fulfillment_dispatch_window_plan "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and idempotency_key=?",
                (rs, row) -> new StoredPlan(rs.getObject("fulfillment_id", UUID.class),
                        rs.getLong("fulfillment_version"), rs.getInt("revision"),
                        rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(),
                        rs.getString("reason"), rs.getObject("actor_membership_id", UUID.class),
                        rs.getTimestamp("recorded_at").toInstant(), rs.getString("request_hash")),
                tenantId, workspaceId, actorMembershipId, idempotencyKey);
        if (values.isEmpty()) return Optional.empty();
        StoredPlan stored = values.getFirst();
        if (!stored.requestHash().equals(requestHash)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
        return Optional.of(view(stored, true));
    }

    @Override
    @Transactional
    public View record(RecordCommand command) {
        List<FulfillmentState> states = jdbc.query("select version,status,sales_order_id from logistics.fulfillment "
                        + "where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new FulfillmentState(rs.getLong("version"), rs.getString("status"),
                        rs.getObject("sales_order_id", UUID.class)),
                command.tenantId(), command.workspaceId(), command.fulfillmentId());
        if (states.isEmpty()) throw error("FULFILLMENT_NOT_FOUND", true);
        FulfillmentState current = states.getFirst();

        Optional<View> replay = findReplay(command.tenantId(), command.workspaceId(), command.actorMembershipId(),
                command.idempotencyKey(), command.requestHash());
        if (replay.isPresent()) return replay.get();
        if (current.version() != command.expectedFulfillmentVersion()) throw error("CONCURRENCY_CONFLICT");
        if (!"READY_FOR_DISPATCH".equals(current.status())) throw error("FULFILLMENT_NOT_READY_FOR_DISPATCH");

        LegacyWindow legacy = legacyWindow(command.tenantId(), command.workspaceId(), command.fulfillmentId(), current.salesOrderId());
        if (legacy != null && (legacy.start() != null || legacy.end() != null)) {
            throw error("FULFILLMENT_DISPATCH_WINDOW_ALREADY_DEFINED");
        }
        Integer currentRevision = jdbc.queryForObject("select max(revision) from logistics.fulfillment_dispatch_window_plan "
                        + "where tenant_id=? and workspace_id=? and fulfillment_id=?",
                Integer.class, command.tenantId(), command.workspaceId(), command.fulfillmentId());
        if (currentRevision != null) throw error("FULFILLMENT_DISPATCH_WINDOW_ALREADY_DEFINED");

        long nextVersion = current.version() + 1;
        int updated = jdbc.update("update logistics.fulfillment set version=?,updated_at=? where tenant_id=? "
                        + "and workspace_id=? and id=? and version=? and status='READY_FOR_DISPATCH'",
                nextVersion, Timestamp.from(command.recordedAt()), command.tenantId(), command.workspaceId(),
                command.fulfillmentId(), current.version());
        if (updated != 1) throw error("CONCURRENCY_CONFLICT");
        jdbc.update("insert into logistics.fulfillment_dispatch_window_plan(id,tenant_id,workspace_id,fulfillment_id,"
                        + "fulfillment_version,revision,window_start,window_end,reason,actor_membership_id,recorded_at,"
                        + "idempotency_key,request_hash) values (?,?,?,?,?,1,?,?,?,?,?,?,?)",
                UUID.randomUUID(), command.tenantId(), command.workspaceId(), command.fulfillmentId(), nextVersion,
                Timestamp.from(command.windowStart()), Timestamp.from(command.windowEnd()), command.reason(),
                command.actorMembershipId(), Timestamp.from(command.recordedAt()), command.idempotencyKey(),
                command.requestHash());
        return new View(command.fulfillmentId(), nextVersion, 1, command.windowStart(), command.windowEnd(),
                command.reason(), command.actorMembershipId(), command.recordedAt(), false);
    }

    private LegacyWindow legacyWindow(UUID tenantId, UUID workspaceId, UUID fulfillmentId, UUID salesOrderId) {
        if (salesOrderId == null) return null;
        List<LegacyWindow> values = jdbc.query("select o.delivery_window_start,o.delivery_window_end "
                        + "from logistics.dispatch_order o where o.tenant_id=? and o.workspace_id=? and o.id=("
                        + "select coalesce((select d.dispatch_order_id from logistics.delivery d where d.tenant_id=? "
                        + "and d.workspace_id=? and d.fulfillment_id=? and d.dispatch_order_id is not null "
                        + "order by d.created_at desc,d.id desc limit 1),(select candidate.id from logistics.dispatch_order candidate "
                        + "where candidate.tenant_id=? and candidate.workspace_id=? and candidate.sales_order_id=? "
                        + "order by candidate.created_at desc,candidate.id desc limit 1))"
                        + ") for update",
                (rs, row) -> new LegacyWindow(instant(rs.getTimestamp("delivery_window_start")),
                        instant(rs.getTimestamp("delivery_window_end"))), tenantId, workspaceId, tenantId, workspaceId,
                fulfillmentId, tenantId, workspaceId, salesOrderId);
        return values.stream().findFirst().orElse(null);
    }

    private static View view(StoredPlan plan, boolean replayed) {
        return new View(plan.fulfillmentId(), plan.fulfillmentVersion(), plan.revision(), plan.start(), plan.end(),
                plan.reason(), plan.actorMembershipId(), plan.recordedAt(), replayed);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static FulfillmentOperationException error(String code) { return error(code, false); }
    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }

    private record FulfillmentState(long version, String status, UUID salesOrderId) { }
    private record LegacyWindow(Instant start, Instant end) { }
    private record StoredPlan(UUID fulfillmentId, long fulfillmentVersion, int revision, Instant start, Instant end,
                              String reason, UUID actorMembershipId, Instant recordedAt, String requestHash) { }
}
