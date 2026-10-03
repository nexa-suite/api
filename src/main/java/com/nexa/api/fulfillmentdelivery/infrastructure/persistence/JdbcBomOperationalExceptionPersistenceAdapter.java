package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Assignee;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Command;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.ExceptionView;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Snapshot;
import com.nexa.api.fulfillmentdelivery.application.port.BomOperationalExceptionPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
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

/** Tenant/workspace SQL for append-only Business Operations Manager coordination events. */
@Repository
@Profile("!test")
public class JdbcBomOperationalExceptionPersistenceAdapter implements BomOperationalExceptionPersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcBomOperationalExceptionPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot list(UUID tenantId, UUID workspaceId) {
        List<ExceptionView> values = jdbc.query(viewSql() + " where c.tenant_id=? and c.workspace_id=? "
                        + "order by c.reported_at,c.id",
                (rs, row) -> view(rs), tenantId, workspaceId);
        return new Snapshot(Instant.now(), values);
    }

    @Override
    @Transactional(readOnly = true)
    public ExceptionView find(UUID tenantId, UUID workspaceId, UUID exceptionId) {
        return jdbc.query(viewSql() + " where c.tenant_id=? and c.workspace_id=? and c.id=?",
                        (rs, row) -> view(rs), tenantId, workspaceId, exceptionId)
                .stream().findFirst().orElseThrow(() -> error("OPERATIONAL_EXCEPTION_NOT_FOUND", true));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Assignee> assignees(UUID tenantId, UUID workspaceId, UUID exceptionId,
            List<WorkforceDirectory.ExceptionAssignee> candidates) {
        UUID deliveryId = jdbc.query("select delivery_id from logistics.operational_exception_case "
                        + "where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, exceptionId)
                .stream().findFirst().orElseThrow(() -> error("OPERATIONAL_EXCEPTION_NOT_FOUND", true));
        List<Assignee> result = new ArrayList<>();
        for (WorkforceDirectory.ExceptionAssignee candidate : candidates) {
            boolean driverReporter = candidate.driverReporter()
                    && isAssigned(tenantId, workspaceId, deliveryId, candidate.membershipId());
            if (candidate.coordinator() || driverReporter) {
                result.add(new Assignee(candidate.membershipId(), candidate.displayName(), candidate.coordinator(), driverReporter));
            }
        }
        return List.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCurrentDriverAssignment(UUID tenantId, UUID workspaceId, UUID exceptionId, UUID membershipId) {
        return jdbc.query("select exists(select 1 from logistics.operational_exception_case c "
                        + "join logistics.delivery_assignment a on a.tenant_id=c.tenant_id "
                        + "and a.workspace_id=c.workspace_id and a.delivery_id=c.delivery_id "
                        + "where c.tenant_id=? and c.workspace_id=? and c.id=? and a.responsible_membership_id=?)",
                (rs, row) -> rs.getBoolean(1), tenantId, workspaceId, exceptionId, membershipId)
                .stream().findFirst().orElse(false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MutationResult mutate(Command command) {
        advisoryLock(command);
        DeliveryRow delivery = lockDelivery(command);
        if (delivery == null || !caseExists(command)) throw error("OPERATIONAL_EXCEPTION_NOT_FOUND", true);
        TransitionRow prior = prior(command);
        if (prior != null) {
            if (!Objects.equals(prior.requestHash(), command.requestHash())) {
                throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            }
            return result(command, prior.id(), true);
        }
        if (delivery.version() != command.expectedDeliveryVersion()) throw error("CONCURRENCY_CONFLICT", false);

        Current current = current(command);
        UUID owner = current.ownerId();
        String nextStatus = current.status();
        String outcome = null;
        String resolution = null;
        switch (command.operation()) {
            case "CLAIM" -> {
                if (!"OPEN".equals(current.status())) throw error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID", false);
                owner = command.actorMembershipId();
                nextStatus = "CLAIMED";
            }
            case "ASSIGN" -> {
                if (!List.of("OPEN", "CLAIMED", "FOLLOW_UP").contains(current.status())) {
                    throw error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID", false);
                }
                owner = command.targetMembershipId();
                nextStatus = "CLAIMED";
            }
            case "FOLLOW_UP" -> {
                requireOwner(current, command);
                if (!List.of("CLAIMED", "FOLLOW_UP").contains(current.status())) {
                    throw error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID", false);
                }
                nextStatus = "FOLLOW_UP";
            }
            case "RESOLVE" -> {
                requireOwner(current, command);
                requireWarningOutcome(command);
                if (!List.of("CLAIMED", "FOLLOW_UP").contains(current.status())) {
                    throw error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID", false);
                }
                nextStatus = "RESOLVED";
                resolution = command.reason();
                outcome = "WARNING_CONDITION_ADDRESSED";
            }
            case "CLOSE" -> {
                requireOwner(current, command);
                requireWarningOutcome(command);
                if (!"RESOLVED".equals(current.status())) throw error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID", false);
                nextStatus = "CLOSED";
                resolution = current.resolution();
                outcome = "WARNING_CONDITION_ADDRESSED";
            }
            default -> throw error("INVALID_REQUEST", false);
        }

        UUID transitionId = UUID.randomUUID();
        long nextVersion = delivery.version() + 1;
        jdbc.update("insert into logistics.operational_exception_coordination_transition(id,tenant_id,workspace_id,"
                        + "delivery_id,exception_id,transition_number,from_status,to_status,command_type,actor_membership_id,"
                        + "responsible_membership_id,occurred_at,reason,note,resolution,outcome,delivery_version,"
                        + "idempotency_key,request_hash) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                transitionId, command.tenantId(), command.workspaceId(), delivery.id(), command.exceptionId(),
                current.transitionNumber() + 1, current.status(), nextStatus, command.operation(),
                command.actorMembershipId(), owner, Timestamp.from(command.occurredAt()), command.reason(),
                command.note(), resolution, outcome, nextVersion, command.idempotencyKey(), command.requestHash());
        if (jdbc.update("update logistics.delivery set version=version+1,updated_at=? where tenant_id=? "
                        + "and workspace_id=? and id=? and version=?", Timestamp.from(command.occurredAt()),
                command.tenantId(), command.workspaceId(), delivery.id(), delivery.version()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        return result(command, transitionId, false);
    }

    private void requireWarningOutcome(Command command) {
        String[] source = jdbc.query("select c.type,c.severity,d.status,exists(select 1 from logistics.delivery_attempt a "
                        + "where a.tenant_id=d.tenant_id and a.workspace_id=d.workspace_id and a.delivery_id=d.id "
                        + "and a.status in ('FINAL','PARTIAL')) has_outcome from logistics.operational_exception_case c "
                        + "join logistics.delivery d on d.tenant_id=c.tenant_id and d.workspace_id=c.workspace_id "
                        + "and d.id=c.delivery_id where c.tenant_id=? and c.workspace_id=? and c.id=?",
                (rs, row) -> new String[]{rs.getString("type"), rs.getString("severity"), rs.getString("status"),
                        Boolean.toString(rs.getBoolean("has_outcome"))},
                command.tenantId(), command.workspaceId(), command.exceptionId()).stream().findFirst()
                .orElseThrow(() -> error("OPERATIONAL_EXCEPTION_NOT_FOUND", true));
        boolean warning = "WARNING".equals(source[1])
                && List.of("DELAY", "INCOMPLETE_INSTRUCTION").contains(source[0]);
        boolean outcome = List.of("DELIVERED", "PARTIAL").contains(source[2]) && Boolean.parseBoolean(source[3]);
        if (!warning || !outcome) throw error("EXCEPTION_OUTCOME_NOT_AUTHORIZED", false);
    }

    private void requireOwner(Current current, Command command) {
        if (!command.actorMembershipId().equals(current.ownerId())) {
            throw error("OPERATIONAL_EXCEPTION_TRANSITION_INVALID", false);
        }
    }

    private Current current(Command command) {
        return jdbc.query("select latest.transition_number,latest.to_status,latest.responsible_membership_id,"
                        + "latest.resolution from logistics.operational_exception_coordination_transition latest "
                        + "where latest.tenant_id=? and latest.workspace_id=? and latest.exception_id=? "
                        + "order by latest.transition_number desc limit 1",
                (rs, row) -> new Current(rs.getInt("transition_number"), rs.getString("to_status"),
                        rs.getObject("responsible_membership_id", UUID.class), rs.getString("resolution")),
                command.tenantId(), command.workspaceId(), command.exceptionId()).stream().findFirst()
                .orElse(new Current(0, "OPEN", null, null));
    }

    private MutationResult result(Command command, UUID transitionId, boolean replayed) {
        return jdbc.query("select t.delivery_version transition_delivery_version," + viewProjection() + " from "
                        + "logistics.operational_exception_coordination_transition t join logistics.operational_exception_case c "
                        + "on c.tenant_id=t.tenant_id and c.workspace_id=t.workspace_id and c.delivery_id=t.delivery_id "
                        + "and c.id=t.exception_id join logistics.delivery d on d.tenant_id=c.tenant_id "
                        + "and d.workspace_id=c.workspace_id and d.id=c.delivery_id "
                        + "left join lateral (select x.transition_number,x.to_status,x.responsible_membership_id,"
                        + "x.resolution,x.outcome,x.occurred_at from logistics.operational_exception_coordination_transition x "
                        + "where x.tenant_id=t.tenant_id and x.workspace_id=t.workspace_id and x.exception_id=t.exception_id "
                        + "and x.transition_number<=t.transition_number order by x.transition_number desc limit 1) latest on true "
                        + "left join lateral (select x.occurred_at from logistics.operational_exception_coordination_transition x "
                        + "where x.tenant_id=t.tenant_id and x.workspace_id=t.workspace_id and x.exception_id=t.exception_id "
                        + "and x.command_type in ('CLAIM','ASSIGN') and x.transition_number<=t.transition_number "
                        + "order by x.transition_number limit 1) claimed on true "
                        + "left join lateral (select x.actor_membership_id,x.occurred_at from logistics.operational_exception_transition x "
                        + "where x.tenant_id=t.tenant_id and x.workspace_id=t.workspace_id and x.exception_id=t.exception_id "
                        + "and x.to_status='UNDER_REVIEW' order by x.transition_number desc limit 1) reviewed on true "
                        + "left join lateral (select x.to_status,x.responsible_membership_id from logistics.operational_exception_transition x "
                        + "where x.tenant_id=t.tenant_id and x.workspace_id=t.workspace_id and x.exception_id=t.exception_id "
                        + "and x.delivery_version<=t.delivery_version order by x.transition_number desc limit 1) driver_state on true "
                        + "where t.tenant_id=? and t.workspace_id=? and t.id=?",
                (rs, row) -> {
                    long version = rs.getLong("transition_delivery_version");
                    return new MutationResult(rs.getObject("delivery_id", UUID.class), version, view(rs, version), replayed);
                }, command.tenantId(), command.workspaceId(), transitionId).stream()
                .findFirst().orElseThrow(() -> error("OPERATIONAL_EXCEPTION_NOT_FOUND", true));
    }

    private String viewSql() {
        return "select " + viewProjection() + " from logistics.operational_exception_case c "
                + "join logistics.delivery d on d.tenant_id=c.tenant_id and d.workspace_id=c.workspace_id "
                + "and d.id=c.delivery_id "
                + "left join lateral (select t.transition_number,t.to_status,t.responsible_membership_id,"
                + "t.resolution,t.outcome,t.occurred_at from logistics.operational_exception_coordination_transition t "
                + "where t.tenant_id=c.tenant_id and t.workspace_id=c.workspace_id and t.exception_id=c.id "
                + "order by t.transition_number desc limit 1) latest on true "
                + "left join lateral (select t.occurred_at from logistics.operational_exception_coordination_transition t "
                + "where t.tenant_id=c.tenant_id and t.workspace_id=c.workspace_id and t.exception_id=c.id "
                + "and t.command_type in ('CLAIM','ASSIGN') order by t.transition_number limit 1) claimed on true "
                + "left join lateral (select t.actor_membership_id,t.occurred_at from logistics.operational_exception_transition t "
                + "where t.tenant_id=c.tenant_id and t.workspace_id=c.workspace_id and t.exception_id=c.id "
                + "and t.to_status='UNDER_REVIEW' order by t.transition_number desc limit 1) reviewed on true "
                + "left join lateral (select t.to_status,t.responsible_membership_id from logistics.operational_exception_transition t "
                + "where t.tenant_id=c.tenant_id and t.workspace_id=c.workspace_id and t.exception_id=c.id "
                + "order by t.transition_number desc limit 1) driver_state on true ";
    }

    private String viewProjection() {
        return "c.id exception_id,c.delivery_id,d.version delivery_version,c.source_kind,c.source_incident_id,"
                + "'DELIVERY' affected_object_type,c.delivery_id affected_object_id,c.type,c.severity,"
                + "coalesce(latest.to_status,driver_state.to_status,'OPEN') status,c.reason,c.description,c.place,"
                + "coalesce(latest.resolution,c.resolution) resolution,coalesce(latest.outcome,c.outcome) outcome,"
                + "c.reported_by_membership_id,c.occurred_at,c.reported_at,"
                + "coalesce(latest.responsible_membership_id,driver_state.responsible_membership_id) responsible_membership_id,"
                + "reviewed.actor_membership_id under_review_by,reviewed.occurred_at under_review_at,"
                + "latest.responsible_membership_id coordination_owner_membership_id,claimed.occurred_at coordination_claimed_at,"
                + "coalesce((select array_agg(e.evidence_object_id order by e.evidence_object_id) from "
                + "logistics.driver_delivery_incident_evidence e where e.tenant_id=c.tenant_id and e.workspace_id=c.workspace_id "
                + "and e.incident_id=c.source_driver_incident_id),array[]::uuid[]) evidence_object_ids";
    }

    private static ExceptionView view(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return view(rs);
    }

    private static ExceptionView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return view(rs, rs.getLong("delivery_version"));
    }

    private static ExceptionView view(java.sql.ResultSet rs, long deliveryVersion) throws java.sql.SQLException {
        return new ExceptionView(rs.getObject("exception_id", UUID.class), rs.getObject("delivery_id", UUID.class),
                deliveryVersion, rs.getString("source_kind"), rs.getObject("source_incident_id", UUID.class),
                rs.getString("affected_object_type"), rs.getObject("affected_object_id", UUID.class), rs.getString("type"),
                rs.getString("severity"), rs.getString("status"), rs.getString("reason"), rs.getString("description"),
                rs.getString("place"), rs.getString("resolution"), rs.getString("outcome"),
                rs.getObject("reported_by_membership_id", UUID.class), instant(rs, "occurred_at"), instant(rs, "reported_at"),
                rs.getObject("responsible_membership_id", UUID.class), instant(rs, "coordination_claimed_at"),
                rs.getObject("under_review_by", UUID.class), instant(rs, "under_review_at"),
                rs.getObject("coordination_owner_membership_id", UUID.class), instant(rs, "coordination_claimed_at"),
                uuidArray(rs.getArray("evidence_object_ids")));
    }

    private boolean isAssigned(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID membershipId) {
        return jdbc.query("select exists(select 1 from logistics.delivery_assignment where tenant_id=? and workspace_id=? "
                        + "and delivery_id=? and responsible_membership_id=?)", (rs, row) -> rs.getBoolean(1),
                tenantId, workspaceId, deliveryId, membershipId).stream().findFirst().orElse(false);
    }

    private boolean caseExists(Command command) {
        return jdbc.query("select exists(select 1 from logistics.operational_exception_case where tenant_id=? "
                        + "and workspace_id=? and id=?)", (rs, row) -> rs.getBoolean(1),
                command.tenantId(), command.workspaceId(), command.exceptionId())
                .stream().findFirst().orElse(false);
    }

    private DeliveryRow lockDelivery(Command command) {
        return jdbc.query("select id,version from logistics.delivery where tenant_id=? and workspace_id=? "
                        + "and id=(select delivery_id from logistics.operational_exception_case where tenant_id=? "
                        + "and workspace_id=? and id=?) for update",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getLong("version")),
                command.tenantId(), command.workspaceId(), command.tenantId(), command.workspaceId(), command.exceptionId())
                .stream().findFirst().orElse(null);
    }

    private TransitionRow prior(Command command) {
        return jdbc.query("select id,request_hash from logistics.operational_exception_coordination_transition "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and command_type=? "
                        + "and idempotency_key=? for update",
                (rs, row) -> new TransitionRow(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                command.tenantId(), command.workspaceId(), command.actorMembershipId(), command.operation(),
                command.idempotencyKey()).stream().findFirst().orElse(null);
    }

    private void advisoryLock(Command command) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                command.tenantId() + "|" + command.workspaceId() + "|" + command.actorMembershipId()
                        + "|BOM_EXCEPTION|" + command.operation() + "|" + command.idempotencyKey());
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<UUID> uuidArray(Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object raw = array.getArray();
        if (!(raw instanceof Object[] values)) return List.of();
        List<UUID> result = new ArrayList<>(values.length);
        for (Object value : values) if (value instanceof UUID id) result.add(id);
        return List.copyOf(result);
    }

    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }

    private record DeliveryRow(UUID id, long version) { }
    private record Current(int transitionNumber, String status, UUID ownerId, String resolution) { }
    private record TransitionRow(UUID id, String requestHash) { }
}
