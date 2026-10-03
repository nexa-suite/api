package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.EvidenceRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.IncidentRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.IncidentView;
import com.nexa.api.fulfillmentdelivery.application.port.DriverDeliveryIncidentPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.DriverIncidentSourceRequest;
import com.nexa.api.fulfillmentdelivery.application.port.OperationalExceptionPersistencePort;
import com.nexa.api.fulfillmentdelivery.domain.operationalexception.OperationalExceptionSourceClassifier;
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

/** Scoped append-only persistence for driver delivery incidents and subject evidence. */
@Repository
@Profile("!test")
public class JdbcDriverDeliveryIncidentPersistenceAdapter implements DriverDeliveryIncidentPersistencePort {
    private static final String INCIDENT_OPERATION = "DRIVER_INCIDENT";
    private static final String EVIDENCE_OPERATION = "DRIVER_INCIDENT_EVIDENCE";
    private final JdbcTemplate jdbc;
    private final OperationalExceptionPersistencePort operationalExceptions;

    public JdbcDriverDeliveryIncidentPersistenceAdapter(JdbcTemplate jdbc,
                                                        OperationalExceptionPersistencePort operationalExceptions) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate is required");
        this.operationalExceptions = Objects.requireNonNull(operationalExceptions,
                "Operational-exception persistence is required");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IncidentView recordIncident(IncidentRequest request) {
        validate(request);
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                INCIDENT_OPERATION, request.idempotencyKey());
        DeliveryRow delivery = lockAssignedDelivery(request.tenantId(), request.workspaceId(),
                request.deliveryId(), request.actorMembershipId());
        Idempotency previous = findIdempotency(request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), INCIDENT_OPERATION, request.idempotencyKey());
        if (previous != null) {
            ensureHash(previous.requestHash(), request.requestHash());
            return loadIncident(request.tenantId(), request.workspaceId(), previous.resourceId(), true, false);
        }
        requireCurrentAttempt(request.tenantId(), request.workspaceId(), request.deliveryId(),
                request.attemptId(), request.actorMembershipId());
        requireVersion(delivery.version(), request.expectedDeliveryVersion());
        if (request.type() == null || request.severity() == null
                || request.severity() != OperationalExceptionSourceClassifier.classify(request.type())) {
            throw error("DRIVER_INCIDENT_TYPE_REQUIRED", false);
        }

        UUID incidentId = UUID.randomUUID();
        long nextDeliveryVersion = delivery.version() + 1;
        jdbc.update("insert into logistics.driver_delivery_incident(id,tenant_id,workspace_id,delivery_id,"
                        + "delivery_attempt_id,reason,description,place,reported_by_membership_id,reported_at,"
                        + "delivery_version,request_hash,incident_type,exception_severity) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                incidentId, request.tenantId(), request.workspaceId(), request.deliveryId(), request.attemptId(),
                request.reason(), request.description(), request.place(), request.actorMembershipId(),
                Timestamp.from(request.recordedAt()), nextDeliveryVersion, request.requestHash(), request.type().name(),
                request.severity().name());
        operationalExceptions.materializeDriverIncident(new DriverIncidentSourceRequest(request.tenantId(),
                request.workspaceId(), request.deliveryId(), incidentId, request.type().name(),
                request.severity().name(), request.reason(), request.description(), request.place(),
                request.actorMembershipId(), request.recordedAt(), nextDeliveryVersion, request.idempotencyKey(),
                request.requestHash()));
        if (jdbc.update("update logistics.delivery set version=version+1,updated_at=? where tenant_id=? "
                        + "and workspace_id=? and id=? and version=?", Timestamp.from(request.recordedAt()),
                request.tenantId(), request.workspaceId(), request.deliveryId(), delivery.version()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        saveIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                INCIDENT_OPERATION, request.idempotencyKey(), request.requestHash(), incidentId, request.recordedAt());
        return loadIncident(request.tenantId(), request.workspaceId(), incidentId, false, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IncidentView findEvidenceReplay(EvidenceRequest request) {
        validate(request);
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                EVIDENCE_OPERATION, request.idempotencyKey());
        lockAssignedDelivery(request.tenantId(), request.workspaceId(), request.deliveryId(),
                request.actorMembershipId());
        Idempotency previous = findIdempotency(request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), EVIDENCE_OPERATION, request.idempotencyKey());
        if (previous == null) return null;
        ensureHash(previous.requestHash(), request.requestHash());
        if (!previous.resourceId().equals(request.incidentId())) throw error("DELIVERY_INCIDENT_NOT_FOUND", true);
        return loadIncident(request.tenantId(), request.workspaceId(), request.incidentId(), true, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IncidentView appendEvidence(EvidenceRequest request) {
        validate(request);
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                EVIDENCE_OPERATION, request.idempotencyKey());
        DeliveryRow delivery = lockAssignedDelivery(request.tenantId(), request.workspaceId(),
                request.deliveryId(), request.actorMembershipId());
        Idempotency previous = findIdempotency(request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), EVIDENCE_OPERATION, request.idempotencyKey());
        if (previous != null) {
            ensureHash(previous.requestHash(), request.requestHash());
            if (!previous.resourceId().equals(request.incidentId())) throw error("DELIVERY_INCIDENT_NOT_FOUND", true);
            return loadIncident(request.tenantId(), request.workspaceId(), request.incidentId(), true, true);
        }
        requireVersion(delivery.version(), request.expectedDeliveryVersion());
        requireIncident(request);
        for (UUID evidenceId : request.evidenceObjectIds()) {
            jdbc.update("insert into logistics.driver_delivery_incident_evidence(tenant_id,workspace_id,incident_id,"
                            + "evidence_object_id,attached_by_membership_id,attached_at) values (?,?,?,?,?,?) "
                            + "on conflict (tenant_id,workspace_id,incident_id,evidence_object_id) do nothing",
                    request.tenantId(), request.workspaceId(), request.incidentId(), evidenceId,
                    request.actorMembershipId(), Timestamp.from(request.attachedAt()));
        }
        saveIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                EVIDENCE_OPERATION, request.idempotencyKey(), request.requestHash(),
                request.incidentId(), request.attachedAt());
        return loadIncident(request.tenantId(), request.workspaceId(), request.incidentId(), false, true);
    }

    private DeliveryRow lockAssignedDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID actorId) {
        return jdbc.query("select d.status,d.version from logistics.delivery d "
                        + "join logistics.delivery_assignment a on a.tenant_id=d.tenant_id "
                        + "and a.workspace_id=d.workspace_id and a.delivery_id=d.id "
                        + "where d.tenant_id=? and d.workspace_id=? and d.id=? "
                        + "and a.responsible_membership_id=? for update of d",
                (rs, row) -> new DeliveryRow(rs.getString("status"), rs.getLong("version")),
                tenantId, workspaceId, deliveryId, actorId).stream().findFirst()
                .orElseThrow(() -> error("DELIVERY_NOT_FOUND", true));
    }

    private void requireCurrentAttempt(UUID tenantId, UUID workspaceId, UUID deliveryId,
                                       UUID attemptId, UUID actorId) {
        Boolean authorized = jdbc.queryForObject("select exists(select 1 from logistics.delivery_active_attempt active "
                        + "join logistics.delivery d on d.tenant_id=active.tenant_id "
                        + "and d.workspace_id=active.workspace_id and d.id=active.delivery_id "
                        + "where active.tenant_id=? and active.workspace_id=? and active.delivery_id=? "
                        + "and active.id=? and active.started_by_membership_id=? "
                        + "and d.status in ('ASSIGNED','DISPATCHED','IN_TRANSIT','PARTIAL')) "
                        + "or exists(select 1 from logistics.delivery_attempt terminal "
                        + "join logistics.delivery_command_idempotency started on started.tenant_id=terminal.tenant_id "
                        + "and started.workspace_id=terminal.workspace_id and started.resource_id=terminal.id "
                        + "where terminal.tenant_id=? and terminal.workspace_id=? and terminal.delivery_id=? "
                        + "and terminal.id=? and terminal.status in ('FINAL','PARTIAL','FAILED','REJECTED') "
                        + "and started.actor_membership_id=? and started.operation='ATTEMPT' "
                        + "and terminal.attempt_number=(select max(latest.attempt_number) "
                        + "from logistics.delivery_attempt latest where latest.tenant_id=terminal.tenant_id "
                        + "and latest.workspace_id=terminal.workspace_id and latest.delivery_id=terminal.delivery_id))",
                Boolean.class, tenantId, workspaceId, deliveryId, attemptId, actorId,
                tenantId, workspaceId, deliveryId, attemptId, actorId);
        if (!Boolean.TRUE.equals(authorized)) throw error("DELIVERY_ATTEMPT_NOT_FOUND", true);
    }

    private void requireIncident(EvidenceRequest request) {
        Boolean exists = jdbc.query("select exists(select 1 from logistics.driver_delivery_incident i "
                        + "where i.tenant_id=? and i.workspace_id=? and i.id=? and i.delivery_id=? "
                        + "and i.delivery_attempt_id=?)",
                (rs, row) -> rs.getBoolean(1), request.tenantId(), request.workspaceId(), request.incidentId(),
                request.deliveryId(), request.attemptId()).stream().findFirst().orElse(false);
        if (!Boolean.TRUE.equals(exists)) throw error("DELIVERY_INCIDENT_NOT_FOUND", true);
    }

    private IncidentView loadIncident(UUID tenantId, UUID workspaceId, UUID incidentId,
                                      boolean replayed, boolean currentDeliveryVersion) {
        IncidentView row = jdbc.query("select i.id,i.delivery_id,i.delivery_attempt_id,i.reason,i.description,"
                        + "i.place,i.reported_by_membership_id,i.reported_at,i.incident_type,i.exception_severity,"
                        + "i.delivery_version,d.version current_delivery_version,c.id operational_exception_id "
                        + "from logistics.driver_delivery_incident i join logistics.delivery d "
                        + "on d.tenant_id=i.tenant_id and d.workspace_id=i.workspace_id and d.id=i.delivery_id "
                        + "left join logistics.operational_exception_case c on c.tenant_id=i.tenant_id "
                        + "and c.workspace_id=i.workspace_id and c.delivery_id=i.delivery_id "
                        + "and c.source_kind='DRIVER_INCIDENT' and c.source_driver_incident_id=i.id "
                        + "where i.tenant_id=? and i.workspace_id=? and i.id=?",
                (rs, index) -> new IncidentView(rs.getObject("id", UUID.class),
                        rs.getObject("operational_exception_id", UUID.class),
                        rs.getObject("delivery_id", UUID.class), rs.getObject("delivery_attempt_id", UUID.class),
                        rs.getString("incident_type") == null ? null : com.nexa.api.fulfillmentdelivery.domain.operationalexception.DriverDeliveryIncidentType.valueOf(rs.getString("incident_type")),
                        rs.getString("exception_severity") == null ? null : com.nexa.api.fulfillmentdelivery.domain.operationalexception.OperationalExceptionSeverity.valueOf(rs.getString("exception_severity")),
                        rs.getString("reason"), rs.getString("description"), rs.getString("place"),
                        rs.getObject("reported_by_membership_id", UUID.class),
                        rs.getTimestamp("reported_at").toInstant(), evidenceIds(tenantId, workspaceId, incidentId),
                        currentDeliveryVersion || rs.getString("incident_type") == null
                                ? rs.getLong("current_delivery_version") : rs.getLong("delivery_version"),
                        replayed), tenantId, workspaceId, incidentId)
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_INCIDENT_NOT_FOUND", true));
        return row;
    }

    private List<UUID> evidenceIds(UUID tenantId, UUID workspaceId, UUID incidentId) {
        return jdbc.query("select evidence_object_id from logistics.driver_delivery_incident_evidence "
                        + "where tenant_id=? and workspace_id=? and incident_id=? order by evidence_object_id",
                (rs, row) -> rs.getObject("evidence_object_id", UUID.class), tenantId, workspaceId, incidentId);
    }

    private void lockCommand(UUID tenantId, UUID workspaceId, UUID actorId, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs, row) -> rs.getObject(1),
                tenantId + "|" + workspaceId + "|" + actorId + "|" + operation + "|" + key);
    }

    private Idempotency findIdempotency(UUID tenantId, UUID workspaceId, UUID actorId,
                                        String operation, String key) {
        return jdbc.query("select request_hash,resource_id from logistics.delivery_command_idempotency "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=?",
                (rs, row) -> new Idempotency(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)),
                tenantId, workspaceId, actorId, operation, key).stream().findFirst().orElse(null);
    }

    private void saveIdempotency(UUID tenantId, UUID workspaceId, UUID actorId, String operation,
                                 String key, String hash, UUID incidentId, Instant now) {
        jdbc.update("insert into logistics.delivery_command_idempotency(tenant_id,workspace_id,actor_membership_id,"
                        + "operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                tenantId, workspaceId, actorId, operation, key, hash, incidentId, Timestamp.from(now));
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) throw error("CONCURRENCY_CONFLICT", false);
    }

    private static void ensureHash(String existing, String requested) {
        if (!Objects.equals(existing, requested)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
    }

    private static void validate(IncidentRequest request) {
        if (request.tenantId() == null || request.workspaceId() == null || request.deliveryId() == null
                || request.attemptId() == null || request.actorMembershipId() == null
                || request.expectedDeliveryVersion() < 0 || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 160
                || request.requestHash() == null || !request.requestHash().matches("[0-9a-f]{64}")
                || request.recordedAt() == null) throw new IllegalArgumentException("Driver incident request is incomplete");
    }

    private static void validate(EvidenceRequest request) {
        if (request.tenantId() == null || request.workspaceId() == null || request.deliveryId() == null
                || request.attemptId() == null || request.incidentId() == null || request.actorMembershipId() == null
                || request.expectedDeliveryVersion() < 0 || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 160
                || request.requestHash() == null || !request.requestHash().matches("[0-9a-f]{64}")
                || request.evidenceObjectIds().isEmpty() || request.attachedAt() == null) {
            throw new IllegalArgumentException("Driver incident evidence request is incomplete");
        }
    }

    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }

    private record DeliveryRow(String status, long version) { }
    private record Idempotency(String requestHash, UUID resourceId) { }
}
