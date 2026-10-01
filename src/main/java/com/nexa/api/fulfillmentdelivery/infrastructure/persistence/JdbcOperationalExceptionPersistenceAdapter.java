package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ClaimRequest;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.DriverIncidentSourceRequest;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ExceptionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ExceptionView;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ReviewRequest;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.TypedIncidentSourceRequest;
import com.nexa.api.fulfillmentdelivery.application.port.OperationalExceptionPersistencePort;
import com.nexa.api.fulfillmentdelivery.domain.incident.IncidentSeverity;
import com.nexa.api.fulfillmentdelivery.domain.incident.IncidentType;
import com.nexa.api.fulfillmentdelivery.domain.operationalexception.OperationalExceptionLifecycle;
import com.nexa.api.fulfillmentdelivery.domain.operationalexception.DriverDeliveryIncidentType;
import com.nexa.api.fulfillmentdelivery.domain.operationalexception.OperationalExceptionSourceClassifier;
import com.nexa.api.fulfillmentdelivery.domain.operationalexception.OperationalExceptionStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant/workspace-scoped SQL for immutable source-linked cases and append-only Driver transitions. */
@Repository
@Profile("!test")
public class JdbcOperationalExceptionPersistenceAdapter implements OperationalExceptionPersistencePort {
    private static final String ACTIVE_DRIVER_STATUSES = "('ASSIGNED','DISPATCHED','IN_TRANSIT')";
    private static final String CLAIM_OPERATION = "OPERATIONAL_EXCEPTION_CLAIM";
    private static final String REVIEW_OPERATION = "OPERATIONAL_EXCEPTION_REVIEW";
    private final JdbcTemplate jdbc;

    public JdbcOperationalExceptionPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate is required");
    }

    @Override
    @Transactional(readOnly = true)
    public ExceptionSetView findForDriver(UUID tenantId, UUID workspaceId, UUID actorMembershipId, UUID deliveryId) {
        List<ExceptionRow> rows = jdbc.query("select d.id delivery_id,d.version delivery_version,"
                        + "c.id exception_id,c.source_kind,c.source_incident_id,c.type,c.severity,c.reason,c.description,"
                        + "c.place,c.resolution,c.outcome,c.reported_by_membership_id,c.occurred_at,c.reported_at,"
                        + "latest.to_status,latest.responsible_membership_id,claimed.occurred_at claimed_at,"
                        + "reviewed.actor_membership_id under_review_by,reviewed.occurred_at under_review_at,"
                        + "coalesce((select array_agg(e.evidence_object_id order by e.evidence_object_id) "
                        + "from logistics.driver_delivery_incident_evidence e where e.tenant_id=c.tenant_id "
                        + "and e.workspace_id=c.workspace_id and e.incident_id=c.source_driver_incident_id),"
                        + "array[]::uuid[]) evidence_object_ids "
                        + "from logistics.delivery d join logistics.delivery_assignment assignment "
                        + "on assignment.tenant_id=d.tenant_id and assignment.workspace_id=d.workspace_id "
                        + "and assignment.delivery_id=d.id left join logistics.operational_exception_case c "
                        + "on c.tenant_id=d.tenant_id and c.workspace_id=d.workspace_id and c.delivery_id=d.id "
                        + "left join lateral (select t.to_status,t.responsible_membership_id,t.transition_number "
                        + "from logistics.operational_exception_transition t where t.tenant_id=d.tenant_id "
                        + "and t.workspace_id=d.workspace_id and t.delivery_id=d.id and t.exception_id=c.id "
                        + "order by t.transition_number desc limit 1) latest on true "
                        + "left join lateral (select t.occurred_at from logistics.operational_exception_transition t "
                        + "where t.tenant_id=d.tenant_id and t.workspace_id=d.workspace_id and t.exception_id=c.id "
                        + "and t.to_status='CLAIMED' order by t.transition_number limit 1) claimed on true "
                        + "left join lateral (select t.actor_membership_id,t.occurred_at "
                        + "from logistics.operational_exception_transition t where t.tenant_id=d.tenant_id "
                        + "and t.workspace_id=d.workspace_id and t.exception_id=c.id and t.to_status='UNDER_REVIEW' "
                        + "order by t.transition_number desc limit 1) reviewed on true "
                        + "where d.tenant_id=? and d.workspace_id=? and d.id=? "
                        + "and assignment.responsible_membership_id=? and d.status in " + ACTIVE_DRIVER_STATUSES + " "
                        + "order by c.occurred_at,c.id",
                (rs, row) -> exceptionRow(rs.getObject("delivery_id", UUID.class), rs.getLong("delivery_version"), rs),
                tenantId, workspaceId, deliveryId, actorMembershipId);
        if (rows.isEmpty()) throw error("DELIVERY_NOT_FOUND", true);
        ExceptionRow first = rows.getFirst();
        return new ExceptionSetView(first.deliveryId(), first.deliveryVersion(), rows.stream()
                .filter(row -> row.exceptionId() != null).map(JdbcOperationalExceptionPersistenceAdapter::view).toList());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MutationResult claim(ClaimRequest request) {
        return transition(new MutationCommand(request.tenantId(), request.workspaceId(), request.deliveryId(),
                request.exceptionId(), request.actorMembershipId(), request.expectedDeliveryVersion(),
                request.idempotencyKey(), request.requestHash(), request.claimedAt(), CLAIM_OPERATION, false));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MutationResult review(ReviewRequest request) {
        return transition(new MutationCommand(request.tenantId(), request.workspaceId(), request.deliveryId(),
                request.exceptionId(), request.actorMembershipId(), request.expectedDeliveryVersion(),
                request.idempotencyKey(), request.requestHash(), request.reviewedAt(), REVIEW_OPERATION, true));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTypedIncident(TypedIncidentSourceRequest request) {
        validateSourceRequest(request);
        IncidentType type;
        IncidentSeverity sourceSeverity;
        try {
            type = IncidentType.valueOf(request.type());
            sourceSeverity = IncidentSeverity.valueOf(request.sourceSeverity());
        } catch (IllegalArgumentException exception) {
            throw error("INVALID_REQUEST", false);
        }

        // A source write, any case snapshot, and the Delivery version advance serialize with Driver commands.
        List<DeliveryRow> deliveries = lockDispatchDeliveries(request.tenantId(), request.workspaceId(),
                request.dispatchOrderId());
        jdbc.update("insert into logistics.delivery_incident(id,tenant_id,workspace_id,dispatch_order_id,incident_type,"
                        + "severity,buyer_visible,description,occurred_at,resolution,created_at,reported_by_membership_id) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?)",
                request.incidentId(), request.tenantId(), request.workspaceId(), request.dispatchOrderId(), type.name(),
                sourceSeverity.name(), request.buyerVisible(), request.description(), Timestamp.from(request.occurredAt()),
                request.resolution(), Timestamp.from(request.reportedAt()), request.reportedByMembershipId());

        OperationalExceptionSourceClassifier.classify(type, sourceSeverity).ifPresent(mapped -> {
            if (request.reportedByMembershipId() == null || deliveries.size() != 1) return;
            DeliveryRow delivery = deliveries.getFirst();
            if (!isCaseEligibleDeliveryStatus(delivery.status())) return;
            UUID exceptionId = UUID.randomUUID();
            jdbc.update("insert into logistics.operational_exception_case(id,tenant_id,workspace_id,delivery_id,"
                            + "source_kind,dispatch_order_id,source_incident_id,source_driver_incident_id,type,severity,"
                            + "reason,description,place,resolution,outcome,reported_by_membership_id,occurred_at,reported_at,opened_at) "
                            + "values (?,?,?,?,'DISPATCH_INCIDENT',?,?,null,?,?,null,?,null,?,null,?,?,?,?)",
                    exceptionId, request.tenantId(), request.workspaceId(), delivery.id(), request.dispatchOrderId(),
                    request.incidentId(), type.name(), mapped.name(), request.description(), request.resolution(),
                    request.reportedByMembershipId(), Timestamp.from(request.occurredAt()),
                    Timestamp.from(request.reportedAt()), Timestamp.from(request.reportedAt()));
            long nextVersion = delivery.version() + 1;
            insertTransition(request.tenantId(), request.workspaceId(), delivery.id(), exceptionId, 1,
                    null, OperationalExceptionStatus.OPEN, request.reportedByMembershipId(), null,
                    request.reportedAt(), "SOURCE_REPORTED", nextVersion, "SOURCE", request.idempotencyKey(),
                    request.requestHash(), UUID.randomUUID());
            incrementDeliveryVersion(request.tenantId(), request.workspaceId(), delivery, request.reportedAt());
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID materializeDriverIncident(DriverIncidentSourceRequest request) {
        validateDriverSourceRequest(request);
        String deliveryStatus = jdbc.query("select status from logistics.delivery where tenant_id=? and workspace_id=? "
                        + "and id=?",
                (rs, row) -> rs.getString("status"), request.tenantId(), request.workspaceId(), request.deliveryId())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_NOT_FOUND", true));
        if (!isDriverActive(deliveryStatus)) return null;
        UUID exceptionId = UUID.randomUUID();
        jdbc.update("insert into logistics.operational_exception_case(id,tenant_id,workspace_id,delivery_id,"
                        + "source_kind,dispatch_order_id,source_incident_id,source_driver_incident_id,type,severity,"
                        + "reason,description,place,resolution,outcome,reported_by_membership_id,occurred_at,reported_at,opened_at) "
                        + "values (?,?,?,?,'DRIVER_INCIDENT',null,?,?,?, ?,?,?,?,null,null,?,?,?,?)",
                exceptionId, request.tenantId(), request.workspaceId(), request.deliveryId(), request.incidentId(),
                request.incidentId(), request.type(), request.severity(), request.reason(), request.description(),
                request.place(), request.reportedByMembershipId(), Timestamp.from(request.reportedAt()),
                Timestamp.from(request.reportedAt()), Timestamp.from(request.reportedAt()));
        insertTransition(request.tenantId(), request.workspaceId(), request.deliveryId(), exceptionId, 1,
                null, OperationalExceptionStatus.OPEN, request.reportedByMembershipId(), null,
                request.reportedAt(), "SOURCE_REPORTED", request.deliveryVersion(), "SOURCE",
                request.idempotencyKey(), request.requestHash(), UUID.randomUUID());
        return exceptionId;
    }

    private MutationResult transition(MutationCommand request) {
        validateCommand(request);
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                request.operation(), request.idempotencyKey());
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery == null || !isDriverActive(delivery.status())
                || !isAssigned(request.tenantId(), request.workspaceId(), request.deliveryId(), request.actorMembershipId())) {
            throw error("DELIVERY_NOT_FOUND", true);
        }
        IdempotencyRow prior = idempotency(request);
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return resultForTransition(request.tenantId(), request.workspaceId(), request.deliveryId(),
                    prior.resourceId(), true);
        }
        if (delivery.version() != request.expectedDeliveryVersion()) throw error("CONCURRENCY_CONFLICT", false);

        ExceptionState current = currentState(request.tenantId(), request.workspaceId(), request.deliveryId(),
                request.exceptionId());
        OperationalExceptionLifecycle.Transition next = (request.review()
                ? OperationalExceptionLifecycle.review(current.status(), current.responsibleMembershipId(), request.actorMembershipId())
                : OperationalExceptionLifecycle.claim(current.status(), current.responsibleMembershipId(), request.actorMembershipId()))
                .orElseThrow(() -> error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID", false));
        UUID transitionId = UUID.randomUUID();
        int nextNumber = current.transitionNumber() + 1;
        long nextVersion = delivery.version() + 1;
        insertTransition(request.tenantId(), request.workspaceId(), request.deliveryId(), request.exceptionId(),
                nextNumber, current.status(), next.status(), next.actorMembershipId(), next.responsibleMembershipId(),
                request.occurredAt(), next.reasonCode(), nextVersion, request.review() ? "REVIEW" : "CLAIM",
                request.idempotencyKey(), request.requestHash(), transitionId);
        incrementDeliveryVersion(request.tenantId(), request.workspaceId(), delivery, request.occurredAt());
        insertIdempotency(request, transitionId);
        return resultForTransition(request.tenantId(), request.workspaceId(), request.deliveryId(), transitionId, false);
    }

    private ExceptionState currentState(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID exceptionId) {
        return jdbc.query("select latest.to_status,latest.responsible_membership_id,latest.transition_number "
                        + "from logistics.operational_exception_case c join lateral (select t.to_status,"
                        + "t.responsible_membership_id,t.transition_number from logistics.operational_exception_transition t "
                        + "where t.tenant_id=c.tenant_id and t.workspace_id=c.workspace_id and t.delivery_id=c.delivery_id "
                        + "and t.exception_id=c.id order by t.transition_number desc limit 1) latest on true "
                        + "where c.tenant_id=? and c.workspace_id=? and c.delivery_id=? and c.id=?",
                (rs, row) -> new ExceptionState(OperationalExceptionStatus.valueOf(rs.getString("to_status")),
                        rs.getObject("responsible_membership_id", UUID.class), rs.getInt("transition_number")),
                tenantId, workspaceId, deliveryId, exceptionId).stream().findFirst()
                .orElseThrow(() -> error("OPERATIONAL_EXCEPTION_NOT_FOUND", true));
    }

    private MutationResult resultForTransition(UUID tenantId, UUID workspaceId, UUID deliveryId,
                                               UUID transitionId, boolean replayed) {
        return jdbc.query("select selected.delivery_id,selected.delivery_version,selected.to_status,selected.responsible_membership_id,"
                        + "c.id exception_id,c.source_kind,c.source_incident_id,c.type,c.severity,c.reason,c.description,"
                        + "c.place,c.resolution,c.outcome,c.reported_by_membership_id,c.occurred_at,c.reported_at,"
                        + "claimed.occurred_at claimed_at,reviewed.actor_membership_id under_review_by,"
                        + "reviewed.occurred_at under_review_at,coalesce((select array_agg(e.evidence_object_id order by e.evidence_object_id) "
                        + "from logistics.driver_delivery_incident_evidence e where e.tenant_id=c.tenant_id "
                        + "and e.workspace_id=c.workspace_id and e.incident_id=c.source_driver_incident_id),array[]::uuid[]) evidence_object_ids "
                        + "from logistics.operational_exception_transition selected join logistics.operational_exception_case c "
                        + "on c.tenant_id=selected.tenant_id and c.workspace_id=selected.workspace_id "
                        + "and c.delivery_id=selected.delivery_id and c.id=selected.exception_id "
                        + "left join lateral (select t.occurred_at from logistics.operational_exception_transition t "
                        + "where t.tenant_id=c.tenant_id and t.workspace_id=c.workspace_id and t.exception_id=c.id "
                        + "and t.to_status='CLAIMED' and t.transition_number<=selected.transition_number "
                        + "order by t.transition_number limit 1) claimed on true "
                        + "left join lateral (select t.actor_membership_id,t.occurred_at from logistics.operational_exception_transition t "
                        + "where t.tenant_id=c.tenant_id and t.workspace_id=c.workspace_id and t.exception_id=c.id "
                        + "and t.to_status='UNDER_REVIEW' and t.transition_number<=selected.transition_number "
                        + "order by t.transition_number desc limit 1) reviewed on true "
                        + "where selected.tenant_id=? and selected.workspace_id=? and selected.delivery_id=? and selected.id=?",
                (rs, row) -> new MutationResult(deliveryId, rs.getLong("delivery_version"),
                        exceptionView(rs, "exception_id", rs.getString("to_status"),
                                rs.getObject("responsible_membership_id", UUID.class)), replayed),
                tenantId, workspaceId, deliveryId, transitionId).stream().findFirst()
                .orElseThrow(() -> error("OPERATIONAL_EXCEPTION_NOT_FOUND", true));
    }

    private List<DeliveryRow> lockDispatchDeliveries(UUID tenantId, UUID workspaceId, UUID dispatchOrderId) {
        return jdbc.query("select id,status,version from logistics.delivery where tenant_id=? and workspace_id=? "
                        + "and dispatch_order_id=? order by id for update",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("version")), tenantId, workspaceId, dispatchOrderId);
    }

    private DeliveryRow lockDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        return jdbc.query("select id,status,version from logistics.delivery where tenant_id=? and workspace_id=? "
                        + "and id=? for update",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("version")), tenantId, workspaceId, deliveryId).stream().findFirst().orElse(null);
    }

    private boolean isAssigned(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID actorMembershipId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.delivery_assignment "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? and responsible_membership_id=?)",
                Boolean.class, tenantId, workspaceId, deliveryId, actorMembershipId));
    }

    private void insertTransition(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID exceptionId,
                                  int transitionNumber, OperationalExceptionStatus from,
                                  OperationalExceptionStatus to, UUID actorMembershipId,
                                  UUID responsibleMembershipId, Instant occurredAt, String reasonCode,
                                  long deliveryVersion, String commandType, String idempotencyKey,
                                  String requestHash, UUID transitionId) {
        jdbc.update("insert into logistics.operational_exception_transition(id,tenant_id,workspace_id,delivery_id,"
                        + "exception_id,transition_number,from_status,to_status,actor_membership_id,responsible_membership_id,"
                        + "occurred_at,reason_code,delivery_version,command_type,idempotency_key,request_hash) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                transitionId, tenantId, workspaceId, deliveryId, exceptionId, transitionNumber,
                from == null ? null : from.name(), to.name(), actorMembershipId, responsibleMembershipId,
                Timestamp.from(occurredAt), reasonCode, deliveryVersion, commandType, idempotencyKey, requestHash);
    }

    private void incrementDeliveryVersion(UUID tenantId, UUID workspaceId, DeliveryRow delivery, Instant at) {
        if (jdbc.update("update logistics.delivery set updated_at=?,version=version+1 where tenant_id=? "
                        + "and workspace_id=? and id=? and version=?", Timestamp.from(at), tenantId, workspaceId,
                delivery.id(), delivery.version()) != 1) throw error("CONCURRENCY_CONFLICT", false);
    }

    private void lockCommand(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenantId + "|" + workspaceId + "|delivery|" + actor + "|" + operation + "|" + key);
    }

    private IdempotencyRow idempotency(MutationCommand request) {
        return jdbc.query("select request_hash,resource_id from logistics.delivery_command_idempotency "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? "
                        + "and idempotency_key=? for update",
                (rs, row) -> new IdempotencyRow(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(),
                request.idempotencyKey()).stream().findFirst().orElse(null);
    }

    private void insertIdempotency(MutationCommand request, UUID transitionId) {
        jdbc.update("insert into logistics.delivery_command_idempotency(tenant_id,workspace_id,actor_membership_id,"
                        + "operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(),
                request.idempotencyKey(), request.requestHash(), transitionId, Timestamp.from(request.occurredAt()));
    }

    private static ExceptionRow exceptionRow(UUID deliveryId, long deliveryVersion, java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ExceptionRow(deliveryId, deliveryVersion, rs.getObject("exception_id", UUID.class),
                rs.getString("source_kind"), rs.getObject("source_incident_id", UUID.class), rs.getString("type"),
                rs.getString("severity"), rs.getString("to_status"), rs.getString("reason"), rs.getString("description"),
                rs.getString("place"), rs.getString("resolution"), rs.getString("outcome"),
                rs.getObject("reported_by_membership_id", UUID.class), instant(rs, "occurred_at"),
                instant(rs, "reported_at"), rs.getObject("responsible_membership_id", UUID.class),
                instant(rs, "claimed_at"), rs.getObject("under_review_by", UUID.class),
                instant(rs, "under_review_at"), uuidArray(rs.getArray("evidence_object_ids")));
    }

    private static ExceptionView exceptionView(java.sql.ResultSet rs, String idColumn, String status,
                                               UUID responsible) throws java.sql.SQLException {
        UUID deliveryId = rs.getObject("delivery_id", UUID.class);
        return new ExceptionView(rs.getObject(idColumn, UUID.class), rs.getString("source_kind"),
                rs.getObject("source_incident_id", UUID.class), "DELIVERY", deliveryId,
                rs.getString("type"), rs.getString("severity"), status, rs.getString("reason"),
                rs.getString("description"), rs.getString("place"), rs.getString("resolution"),
                rs.getString("outcome"), rs.getObject("reported_by_membership_id", UUID.class),
                instant(rs, "occurred_at"), instant(rs, "reported_at"), responsible,
                instant(rs, "claimed_at"), rs.getObject("under_review_by", UUID.class),
                instant(rs, "under_review_at"), uuidArray(rs.getArray("evidence_object_ids")));
    }

    private static ExceptionView view(ExceptionRow row) {
        if (row.exceptionId() == null) return null;
        return new ExceptionView(row.exceptionId(), row.sourceKind(), row.sourceIncidentId(), "DELIVERY",
                row.deliveryId(), row.type(), row.severity(), row.status(), row.reason(), row.description(),
                row.place(), row.resolution(), row.outcome(), row.reportedByMembershipId(), row.occurredAt(),
                row.reportedAt(), row.responsibleMembershipId(), row.claimedAt(), row.underReviewByMembershipId(),
                row.underReviewAt(), row.evidenceObjectIds());
    }

    private static List<UUID> uuidArray(Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object value = array.getArray();
        if (!(value instanceof Object[] values)) return List.of();
        List<UUID> result = new ArrayList<>(values.length);
        for (Object item : values) {
            if (item instanceof UUID uuid) result.add(uuid);
            else if (item != null) result.add(UUID.fromString(item.toString()));
        }
        return List.copyOf(result);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static boolean isDriverActive(String status) {
        return "ASSIGNED".equals(status) || "DISPATCHED".equals(status) || "IN_TRANSIT".equals(status);
    }

    private static boolean isCaseEligibleDeliveryStatus(String status) {
        return "PLANNED".equals(status) || isDriverActive(status);
    }

    private static void validateCommand(MutationCommand request) {
        if (request.tenantId() == null || request.workspaceId() == null || request.deliveryId() == null
                || request.exceptionId() == null || request.actorMembershipId() == null
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 160 || request.requestHash() == null
                || !request.requestHash().matches("[0-9a-f]{64}") || request.occurredAt() == null
                || request.expectedDeliveryVersion() < 0) throw error("INVALID_REQUEST", false);
    }

    private static void validateSourceRequest(TypedIncidentSourceRequest request) {
        if (request == null || request.tenantId() == null || request.workspaceId() == null
                || request.dispatchOrderId() == null || request.incidentId() == null || request.type() == null
                || request.sourceSeverity() == null || request.description() == null || request.description().isBlank()
                || request.reportedByMembershipId() == null || request.occurredAt() == null || request.reportedAt() == null
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 160 || request.requestHash() == null
                || !request.requestHash().matches("[0-9a-f]{64}")) throw error("INVALID_REQUEST", false);
    }

    private static void validateDriverSourceRequest(DriverIncidentSourceRequest request) {
        if (request == null || request.tenantId() == null || request.workspaceId() == null
                || request.deliveryId() == null || request.incidentId() == null || request.type() == null
                || request.severity() == null || request.reason() == null || request.description() == null
                || request.place() == null || request.reportedByMembershipId() == null || request.reportedAt() == null
                || request.deliveryVersion() < 0 || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.requestHash() == null || !request.requestHash().matches("[0-9a-f]{64}")) {
            throw error("INVALID_REQUEST", false);
        }
        try {
            if (OperationalExceptionSourceClassifier.classify(DriverDeliveryIncidentType.valueOf(request.type()))
                    .name().equals(request.severity())) return;
        } catch (IllegalArgumentException ignored) {
            // Convert invalid source classifications into a stable request error below.
        }
        throw error("INVALID_REQUEST", false);
    }

    private static void ensureHash(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
    }

    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }

    private record DeliveryRow(UUID id, String status, long version) { }
    private record ExceptionRow(UUID deliveryId, long deliveryVersion, UUID exceptionId, String sourceKind,
                                UUID sourceIncidentId, String type, String severity, String status, String reason,
                                String description, String place, String resolution, String outcome,
                                UUID reportedByMembershipId, Instant occurredAt, Instant reportedAt,
                                UUID responsibleMembershipId, Instant claimedAt, UUID underReviewByMembershipId,
                                Instant underReviewAt, List<UUID> evidenceObjectIds) { }
    private record ExceptionState(OperationalExceptionStatus status, UUID responsibleMembershipId,
                                  int transitionNumber) { }
    private record IdempotencyRow(String requestHash, UUID resourceId) { }
    private record MutationCommand(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID exceptionId,
                                   UUID actorMembershipId, long expectedDeliveryVersion, String idempotencyKey,
                                   String requestHash, Instant occurredAt, String operation, boolean review) { }
}
