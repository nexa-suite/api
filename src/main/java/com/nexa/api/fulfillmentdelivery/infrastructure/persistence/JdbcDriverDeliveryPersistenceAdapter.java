package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.AttemptStartRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.AttemptStartResult;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.AttemptView;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.DeliveryView;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.OutcomeLineView;
import com.nexa.api.fulfillmentdelivery.application.port.DriverDeliveryPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Scoped persistence for current driver assignments and one active attempt per delivery. */
@Repository
@Profile("!test")
public class JdbcDriverDeliveryPersistenceAdapter implements DriverDeliveryPersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcDriverDeliveryPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate is required");
    }

    @Override
    public List<DeliveryView> listAssigned(UUID tenantId, UUID workspaceId, UUID membershipId) {
        return jdbc.query("select " + DELIVERY_COLUMNS + ACTIVE_COLUMNS + " from logistics.delivery d "
                        + "left join logistics.fulfillment f on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id "
                        + "left join logistics.dispatch_order o on o.tenant_id=d.tenant_id and o.workspace_id=d.workspace_id and o.id=d.dispatch_order_id "
                        + "join logistics.delivery_assignment a on a.tenant_id=d.tenant_id and a.workspace_id=d.workspace_id and a.delivery_id=d.id "
                        + "left join logistics.delivery_active_attempt active on active.tenant_id=d.tenant_id and active.workspace_id=d.workspace_id and active.delivery_id=d.id "
                        + "where d.tenant_id=? and d.workspace_id=? and a.responsible_membership_id=? "
                        + "and d.status in ('ASSIGNED','DISPATCHED','IN_TRANSIT','PARTIAL') order by d.scheduled_at nulls last,d.created_at,d.id",
                JdbcDriverDeliveryPersistenceAdapter::mapDelivery, tenantId, workspaceId, membershipId)
                .stream().map(delivery -> withOutcomeLines(delivery, tenantId, workspaceId)).toList();
    }

    @Override
    public DeliveryView findAssigned(UUID tenantId, UUID workspaceId, UUID membershipId, UUID deliveryId) {
        return jdbc.query("select " + DELIVERY_COLUMNS + ACTIVE_COLUMNS + " from logistics.delivery d "
                        + "left join logistics.fulfillment f on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id "
                        + "left join logistics.dispatch_order o on o.tenant_id=d.tenant_id and o.workspace_id=d.workspace_id and o.id=d.dispatch_order_id "
                        + "join logistics.delivery_assignment a on a.tenant_id=d.tenant_id and a.workspace_id=d.workspace_id and a.delivery_id=d.id "
                        + "left join logistics.delivery_active_attempt active on active.tenant_id=d.tenant_id and active.workspace_id=d.workspace_id and active.delivery_id=d.id "
                        + "where d.tenant_id=? and d.workspace_id=? and a.responsible_membership_id=? and d.id=?",
                JdbcDriverDeliveryPersistenceAdapter::mapDelivery, tenantId, workspaceId, membershipId, deliveryId)
                .stream().findFirst().map(delivery -> withOutcomeLines(delivery, tenantId, workspaceId))
                .orElseThrow(() -> error("DELIVERY_NOT_FOUND"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAssignedAttempt(UUID tenantId, UUID workspaceId, UUID membershipId,
                                       UUID deliveryId, UUID attemptId, String idempotencyKey) {
        DeliveryRow delivery = lockDelivery(tenantId, workspaceId, deliveryId);
        if (delivery == null || !isAssigned(tenantId, workspaceId, deliveryId, membershipId)) {
            throw error("DELIVERY_NOT_FOUND");
        }
        List<UUID> active = jdbc.query("select id from logistics.delivery_active_attempt "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? "
                        + "and started_by_membership_id=? for update",
                (rs, row) -> rs.getObject("id", UUID.class), tenantId, workspaceId,
                deliveryId, membershipId);
        if (active.contains(attemptId)) return;

        boolean sameCommandAlreadyRecorded = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from logistics.delivery_command_idempotency i "
                        + "join logistics.delivery_attempt a on a.tenant_id=i.tenant_id "
                        + "and a.workspace_id=i.workspace_id and a.id=i.resource_id "
                        + "where i.tenant_id=? and i.workspace_id=? and i.actor_membership_id=? "
                        + "and i.operation='ATTEMPT' and i.idempotency_key=? "
                        + "and i.resource_id=? and a.delivery_id=?)",
                Boolean.class, tenantId, workspaceId, membershipId, idempotencyKey, attemptId, deliveryId));
        if (!sameCommandAlreadyRecorded) throw error("DELIVERY_ATTEMPT_NOT_FOUND");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AttemptStartResult startAttempt(AttemptStartRequest request) {
        validate(request);
        lockCommand(request);
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery == null || !isAssigned(request.tenantId(), request.workspaceId(), request.deliveryId(), request.actorMembershipId())) {
            throw error("DELIVERY_NOT_FOUND");
        }

        IdempotencyRow previous = idempotency(request);
        if (previous != null) {
            ensureHash(previous.requestHash(), request.requestHash());
            AttemptView attempt = findActiveAttempt(request.tenantId(), request.workspaceId(), request.deliveryId(), previous.resourceId());
            if (attempt == null) attempt = findTerminalAttempt(request.tenantId(), request.workspaceId(), request.deliveryId(), previous.resourceId());
            if (attempt == null) throw error("DELIVERY_ATTEMPT_NOT_FOUND");
            return new AttemptStartResult(loadAssigned(request), attempt, true);
        }

        AttemptView current = findCurrentActiveAttempt(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (current != null) {
            insertIdempotency(request, current.id());
            return new AttemptStartResult(loadAssigned(request), current, true);
        }
        if (delivery.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT");
        if (!isReady(delivery.status())) throw error("DELIVERY_NOT_READY");

        Integer attemptNumber = jdbc.queryForObject("select coalesce(max(attempt_number),0)+1 from logistics.delivery_attempt where tenant_id=? and workspace_id=? and delivery_id=?",
                Integer.class, request.tenantId(), request.workspaceId(), request.deliveryId());
        Instant startedAt = request.startedAt() == null ? Instant.now() : request.startedAt();
        UUID attemptId = UUID.randomUUID();
        jdbc.update("insert into logistics.delivery_active_attempt(id,tenant_id,workspace_id,delivery_id,attempt_number,started_by_membership_id,started_at,idempotency_key,request_hash) values (?,?,?,?,?,?,?,?,?)",
                attemptId, request.tenantId(), request.workspaceId(), request.deliveryId(), attemptNumber,
                request.actorMembershipId(), Timestamp.from(startedAt), request.idempotencyKey(), request.requestHash());
        if (jdbc.update("update logistics.delivery set updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                Timestamp.from(startedAt), request.tenantId(), request.workspaceId(), request.deliveryId(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        jdbc.update("insert into logistics.delivery_event(id,tenant_id,workspace_id,delivery_id,event_type,actor_membership_id,reason,occurred_at) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.deliveryId(),
                "DELIVERY_ATTEMPT_STARTED", request.actorMembershipId(), "Driver began assigned delivery", Timestamp.from(startedAt));
        insertIdempotency(request, attemptId);
        return new AttemptStartResult(loadAssigned(request),
                new AttemptView(attemptId, attemptNumber, "ACTIVE", request.actorMembershipId(), startedAt), false);
    }

    private DeliveryView loadAssigned(AttemptStartRequest request) {
        return findAssigned(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.deliveryId());
    }

    private DeliveryView withOutcomeLines(DeliveryView delivery, UUID tenantId, UUID workspaceId) {
        if (delivery.fulfillmentId() == null) return delivery;
        List<OutcomeLineView> lines = jdbc.query(
                "select id,sku_id,catalog_item_id,dispatched_quantity,delivered_quantity,rejected_quantity,"
                        + "cancelled_quantity,greatest(dispatched_quantity-delivered_quantity-rejected_quantity-cancelled_quantity,0) remaining_quantity,unit "
                        + "from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? order by id",
                (rs, row) -> new OutcomeLineView(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getBigDecimal("dispatched_quantity"),
                        rs.getBigDecimal("delivered_quantity"), rs.getBigDecimal("rejected_quantity"),
                        rs.getBigDecimal("cancelled_quantity"), rs.getBigDecimal("remaining_quantity"), rs.getString("unit")),
                tenantId, workspaceId, delivery.fulfillmentId());
        return new DeliveryView(delivery.id(), delivery.fulfillmentId(), delivery.salesOrderId(), delivery.status(),
                delivery.destinationSnapshot(), delivery.scheduledAt(), delivery.dispatchedAt(), delivery.deliveredAt(),
                delivery.updatedAt(), delivery.version(), delivery.activeAttempt(), lines);
    }

    private DeliveryRow lockDelivery(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query("select id,status,version from logistics.delivery where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("version")),
                tenant, workspace, id).stream().findFirst().orElse(null);
    }

    private boolean isAssigned(UUID tenant, UUID workspace, UUID delivery, UUID membership) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.delivery_assignment "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and responsible_membership_id=?)",
                Boolean.class, tenant, workspace, delivery, membership));
    }

    private void lockCommand(AttemptStartRequest request) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                request.tenantId() + "|" + request.workspaceId() + "|driver-attempt-start|"
                        + request.actorMembershipId() + "|" + request.idempotencyKey());
    }

    private IdempotencyRow idempotency(AttemptStartRequest request) {
        return jdbc.query("select request_hash,resource_id from logistics.delivery_command_idempotency "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and operation='DRIVER_ATTEMPT_START' and idempotency_key=?",
                (rs, row) -> new IdempotencyRow(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey())
                .stream().findFirst().orElse(null);
    }

    private AttemptView findCurrentActiveAttempt(UUID tenant, UUID workspace, UUID delivery) {
        return jdbc.query("select id,attempt_number,started_by_membership_id,started_at from logistics.delivery_active_attempt "
                        + "where tenant_id=? and workspace_id=? and delivery_id=?",
                (rs, row) -> new AttemptView(rs.getObject("id", UUID.class), rs.getInt("attempt_number"), "ACTIVE",
                        rs.getObject("started_by_membership_id", UUID.class), rs.getTimestamp("started_at").toInstant()),
                tenant, workspace, delivery).stream().findFirst().orElse(null);
    }

    private AttemptView findActiveAttempt(UUID tenant, UUID workspace, UUID delivery, UUID id) {
        return jdbc.query("select id,attempt_number,started_by_membership_id,started_at from logistics.delivery_active_attempt "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and id=?",
                (rs, row) -> new AttemptView(rs.getObject("id", UUID.class), rs.getInt("attempt_number"), "ACTIVE",
                        rs.getObject("started_by_membership_id", UUID.class), rs.getTimestamp("started_at").toInstant()),
                tenant, workspace, delivery, id).stream().findFirst().orElse(null);
    }

    private AttemptView findTerminalAttempt(UUID tenant, UUID workspace, UUID delivery, UUID id) {
        return jdbc.query("select id,attempt_number,status,attempted_at from logistics.delivery_attempt "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and id=?",
                (rs, row) -> new AttemptView(rs.getObject("id", UUID.class), rs.getInt("attempt_number"), rs.getString("status"),
                        null, instant(rs, "attempted_at")), tenant, workspace, delivery, id)
                .stream().findFirst().orElse(null);
    }

    private void insertIdempotency(AttemptStartRequest request, UUID resourceId) {
        jdbc.update("insert into logistics.delivery_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,'DRIVER_ATTEMPT_START',?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey(),
                request.requestHash(), resourceId, Timestamp.from(request.startedAt() == null ? Instant.now() : request.startedAt()));
    }

    private static DeliveryView mapDelivery(ResultSet rs, int row) throws SQLException {
        UUID activeId = rs.getObject("active_id", UUID.class);
        AttemptView active = activeId == null ? null : new AttemptView(activeId, rs.getInt("active_attempt_number"),
                "ACTIVE", rs.getObject("active_started_by", UUID.class), instant(rs, "active_started_at"));
        return new DeliveryView(rs.getObject("id", UUID.class), rs.getObject("fulfillment_id", UUID.class),
                rs.getObject("sales_order_id", UUID.class), rs.getString("status"), rs.getString("destination_snapshot"),
                instant(rs, "scheduled_at"), instant(rs, "dispatched_at"), instant(rs, "delivered_at"),
                instant(rs, "updated_at"), rs.getLong("version"), active, List.of());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void validate(AttemptStartRequest request) {
        if (request.tenantId() == null || request.workspaceId() == null || request.deliveryId() == null
                || request.actorMembershipId() == null || request.expectedVersion() < 0
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 160 || request.requestHash() == null
                || !request.requestHash().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Driver attempt request is incomplete");
    }

    private static void ensureHash(String stored, String actual) {
        if (!Objects.equals(stored, actual)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }

    private static boolean isReady(String status) {
        return "ASSIGNED".equals(status) || "DISPATCHED".equals(status) || "IN_TRANSIT".equals(status) || "PARTIAL".equals(status);
    }

    private static FulfillmentOperationException error(String code) {
        return new FulfillmentOperationException(code,
                "DELIVERY_NOT_FOUND".equals(code) || "DELIVERY_ATTEMPT_NOT_FOUND".equals(code));
    }

    private record DeliveryRow(UUID id, String status, long version) { }
    private record IdempotencyRow(String requestHash, UUID resourceId) { }

    private static final String DELIVERY_COLUMNS = "d.id,d.fulfillment_id,coalesce(f.sales_order_id,o.sales_order_id) sales_order_id,d.status,d.destination_snapshot,d.scheduled_at,d.dispatched_at,d.delivered_at,d.updated_at,d.version,";
    private static final String ACTIVE_COLUMNS = "active.id active_id,active.attempt_number active_attempt_number,active.started_by_membership_id active_started_by,active.started_at active_started_at ";
}
