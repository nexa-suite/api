package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels.CompatibilityAttestationView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels.HistoryEvent;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels.LoadView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels.StopView;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryLoadPersistencePort;
import com.nexa.api.fulfillmentdelivery.domain.load.DeliveryLoadStatus;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityAttestation;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityEvaluator;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityEvaluator.DeliveryFacts;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Scoped SQL for mutable load state, current stop order, and append-only operational history. */
@Repository
@Profile("!test")
public class JdbcDeliveryLoadPersistenceAdapter implements DeliveryLoadPersistencePort {
    private static final String LOAD_ACTIVE_STATUSES = "('DRAFT','ASSIGNED','OFFERED','HANDOFF_CONFIRMED','DRIVER_ACCEPTED','RESPONSIBILITY_TRANSFERRED')";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDeliveryLoadPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper is required");
    }

    @Override
    @Transactional
    public List<DeliveryFacts> lockCandidateFacts(UUID tenantId, UUID workspaceId, List<UUID> fulfillmentIds) {
        String in = in(fulfillmentIds.size());
        List<Object> args = scoped(tenantId, workspaceId, fulfillmentIds);
        List<UUID> locked = jdbc.query("select id from logistics.fulfillment where tenant_id=? and workspace_id=? "
                        + "and id in (" + in + ") order by id for update",
                (rs, row) -> rs.getObject("id", UUID.class), args.toArray());
        if (locked.size() != fulfillmentIds.size()) throw error("FULFILLMENT_NOT_FOUND", true);

        List<UUID> dispatchOrders = jdbc.query("select distinct selected.id from logistics.fulfillment f "
                        + "join lateral (select d.id from logistics.dispatch_order d where d.tenant_id=f.tenant_id "
                        + "and d.workspace_id=f.workspace_id and d.sales_order_id=f.sales_order_id "
                        + "order by d.created_at desc,d.id desc limit 1) selected on true "
                        + "where f.tenant_id=? and f.workspace_id=? and f.id in (" + in + ") order by selected.id",
                (rs, row) -> rs.getObject("id", UUID.class), args.toArray());
        if (!dispatchOrders.isEmpty()) {
            jdbc.query("select id from logistics.dispatch_order where tenant_id=? and workspace_id=? and id in ("
                            + in(dispatchOrders.size()) + ") order by id for share",
                    (rs, row) -> rs.getObject("id", UUID.class), scoped(tenantId, workspaceId, dispatchOrders).toArray());
        }
        jdbc.query("select id from logistics.delivery where tenant_id=? and workspace_id=? and fulfillment_id in ("
                        + in + ") order by id for update",
                (rs, row) -> rs.getObject("id", UUID.class), args.toArray());
        return candidateFacts(tenantId, workspaceId, fulfillmentIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoadView> listForDispatch(UUID tenantId, UUID workspaceId) {
        return loadIds("select id from logistics.delivery_load where tenant_id=? and workspace_id=? "
                        + "order by updated_at desc,id desc", tenantId, workspaceId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoadView> listForDriver(UUID tenantId, UUID workspaceId, UUID driverMembershipId) {
        return loadIds("select distinct l.id from logistics.delivery_load l join logistics.delivery_load_stop s "
                        + "on s.tenant_id=l.tenant_id and s.workspace_id=l.workspace_id and s.load_id=l.id "
                        + "join logistics.delivery d on d.tenant_id=s.tenant_id and d.workspace_id=s.workspace_id "
                        + "and d.id=s.delivery_id where l.tenant_id=? and l.workspace_id=? "
                        + "and l.assigned_driver_membership_id=? and d.status in ('PLANNED','ASSIGNED','DISPATCHED','IN_TRANSIT') "
                        + "order by l.id desc", tenantId, workspaceId, driverMembershipId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoadView> find(UUID tenantId, UUID workspaceId, UUID loadId) {
        List<UUID> ids = jdbc.query("select id from logistics.delivery_load where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> rs.getObject("id", UUID.class), tenantId, workspaceId, loadId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(load(tenantId, workspaceId, loadId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoadView> findReplay(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                                        String operation, String idempotencyKey, String requestHash) {
        List<IdempotencyRow> rows = jdbc.query("select request_hash,response_json from logistics.delivery_load_command_idempotency "
                        + "where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=?",
                (rs, row) -> new IdempotencyRow(rs.getString("request_hash"), rs.getString("response_json")),
                tenantId, workspaceId, actorMembershipId, operation, idempotencyKey);
        if (rows.isEmpty()) return Optional.empty();
        ensureHash(rows.getFirst().requestHash(), requestHash);
        return Optional.of(readView(rows.getFirst().responseJson()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LoadView create(CreateLoadCommand command) {
        lockIdempotency(command.tenantId(), command.workspaceId(), command.actorMembershipId(), "DELIVERY_LOAD_CREATE",
                command.idempotencyKey());
        Optional<LoadView> replay = replay(command.tenantId(), command.workspaceId(), command.actorMembershipId(),
                "DELIVERY_LOAD_CREATE", command.idempotencyKey(), command.requestHash());
        if (replay.isPresent()) return replay.get();

        List<DeliveryFacts> lockedFacts = lockCandidateFacts(command.tenantId(), command.workspaceId(), command.fulfillmentIds());
        Map<UUID, DeliveryFacts> currentByFulfillment = new LinkedHashMap<>();
        lockedFacts.forEach(fact -> currentByFulfillment.put(fact.fulfillmentId(), fact));
        Map<UUID, DeliveryFacts> byFulfillment = new LinkedHashMap<>();
        if (command.compatibilityFacts().size() != command.fulfillmentIds().size()) {
            throw error("LOAD_COMPATIBILITY_FACTS_UNAVAILABLE", false);
        }
        for (DeliveryFacts evaluated : command.compatibilityFacts()) {
            DeliveryFacts current = currentByFulfillment.get(evaluated.fulfillmentId());
            if (current == null || !matchesLockedFacts(current, evaluated)
                    || !Objects.equals(evaluated.originWarehouseId(), command.originWarehouseId())) {
                throw error("CONCURRENCY_CONFLICT", false);
            }
            byFulfillment.put(evaluated.fulfillmentId(), evaluated);
        }
        for (int i = 0; i < command.fulfillmentIds().size(); i++) {
            DeliveryFacts fact = byFulfillment.get(command.fulfillmentIds().get(i));
            if (fact == null || fact.fulfillmentVersion() != command.expectedFulfillmentVersions().get(i)) {
                throw error("CONCURRENCY_CONFLICT", false);
            }
        }
        LoadCompatibilityEvaluator.Evaluation compatibility = LoadCompatibilityEvaluator.evaluate(
                command.fulfillmentIds().stream().map(byFulfillment::get).toList());
        if (!compatibility.compatible()) throw error("FULFILLMENT_TRANSITION_INVALID", false);

        Map<UUID, PlannedDelivery> deliveries = new LinkedHashMap<>();
        for (UUID fulfillmentId : command.fulfillmentIds()) {
            DeliveryFacts fact = byFulfillment.get(fulfillmentId);
            deliveries.put(fulfillmentId, ensurePlannedDelivery(command.tenantId(), command.workspaceId(),
                    fulfillmentId, fact.deliveryId(), command.occurredAt()));
        }

        LoadCompatibilityAttestation attestation = command.compatibilityAttestation();
        jdbc.update("insert into logistics.delivery_load(id,tenant_id,workspace_id,origin_warehouse_id,status,version,"
                        + "capacity_sufficient,handling_compatible,zone_reasonable,no_exclusive_transport_restriction,"
                        + "attested_by_membership_id,attested_at,attestation_observation,created_by_membership_id,created_at,updated_at) "
                        + "values (?,?,?,?,'DRAFT',0,?,?,?,?,?,?,?,?,?,?)",
                command.loadId(), command.tenantId(), command.workspaceId(), command.originWarehouseId(),
                attestation.capacitySufficient(), attestation.handlingCompatible(), attestation.zoneReasonable(),
                attestation.noExclusiveTransportRestriction(), attestation.attestedByMembershipId(),
                Timestamp.from(attestation.attestedAt()), attestation.observation(), command.actorMembershipId(),
                Timestamp.from(command.occurredAt()), Timestamp.from(command.occurredAt()));

        List<UUID> createdStopOrder = new ArrayList<>();
        for (int index = 0; index < command.stopOrder().size(); index++) {
            UUID fulfillmentId = command.stopOrder().get(index);
            PlannedDelivery delivery = deliveries.get(fulfillmentId);
            createdStopOrder.add(delivery.deliveryId());
            jdbc.update("insert into logistics.delivery_load_stop(id,tenant_id,workspace_id,load_id,fulfillment_id,"
                            + "delivery_id,position,delivery_version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), command.tenantId(), command.workspaceId(), command.loadId(), fulfillmentId,
                    delivery.deliveryId(), index + 1, delivery.version(), Timestamp.from(command.occurredAt()),
                    Timestamp.from(command.occurredAt()));
        }
        insertEvent(command.tenantId(), command.workspaceId(), command.loadId(), 1, "CREATED", null,
                DeliveryLoadStatus.DRAFT, command.actorMembershipId(), null, null, command.reason(), List.of(),
                createdStopOrder, attestation, command.occurredAt());
        LoadView result = load(command.tenantId(), command.workspaceId(), command.loadId());
        remember(command.tenantId(), command.workspaceId(), command.actorMembershipId(), "DELIVERY_LOAD_CREATE",
                command.idempotencyKey(), command.requestHash(), result, command.occurredAt());
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LoadView reorder(ReorderStopsCommand command) {
        lockIdempotency(command.tenantId(), command.workspaceId(), command.actorMembershipId(), "DELIVERY_LOAD_REORDER",
                command.idempotencyKey());
        Optional<LoadView> replay = replay(command.tenantId(), command.workspaceId(), command.actorMembershipId(),
                "DELIVERY_LOAD_REORDER", command.idempotencyKey(), command.requestHash());
        if (replay.isPresent()) return replay.get();
        LoadHeader header = lockLoad(command.tenantId(), command.workspaceId(), command.loadId());
        checkVersion(header, command.expectedVersion());
        if (terminalStopExists(command.tenantId(), command.workspaceId(), command.loadId())) {
            throw error("FULFILLMENT_TRANSITION_INVALID", false);
        }
        LoadView before = load(command.tenantId(), command.workspaceId(), command.loadId());
        List<UUID> previousOrder = before.stops().stream().map(StopView::deliveryId).toList();
        Map<UUID, StopView> byFulfillment = new LinkedHashMap<>();
        before.stops().forEach(stop -> byFulfillment.put(stop.fulfillmentId(), stop));
        if (command.stopOrder().size() != byFulfillment.size()
                || command.stopOrder().stream().anyMatch(id -> !byFulfillment.containsKey(id))) {
            throw error("INVALID_REQUEST", false);
        }
        jdbc.update("delete from logistics.delivery_load_stop where tenant_id=? and workspace_id=? and load_id=?",
                command.tenantId(), command.workspaceId(), command.loadId());
        List<UUID> newOrder = new ArrayList<>();
        for (int index = 0; index < command.stopOrder().size(); index++) {
            UUID fulfillmentId = command.stopOrder().get(index);
            StopView stop = byFulfillment.get(fulfillmentId);
            newOrder.add(stop.deliveryId());
            jdbc.update("insert into logistics.delivery_load_stop(id,tenant_id,workspace_id,load_id,fulfillment_id,"
                            + "delivery_id,position,delivery_version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), command.tenantId(), command.workspaceId(), command.loadId(), fulfillmentId,
                    stop.deliveryId(), index + 1, stop.deliveryVersion(), Timestamp.from(command.occurredAt()),
                    Timestamp.from(command.occurredAt()));
        }
        long nextVersion = header.version() + 1;
        updateVersion(command.tenantId(), command.workspaceId(), command.loadId(), header.version(), nextVersion,
                command.occurredAt());
        insertEvent(command.tenantId(), command.workspaceId(), command.loadId(), nextVersion + 1, "STOPS_REORDERED",
                header.status(), header.status(), command.actorMembershipId(), null, null, command.reason(),
                previousOrder, newOrder, null, command.occurredAt());
        LoadView result = load(command.tenantId(), command.workspaceId(), command.loadId());
        remember(command.tenantId(), command.workspaceId(), command.actorMembershipId(), "DELIVERY_LOAD_REORDER",
                command.idempotencyKey(), command.requestHash(), result, command.occurredAt());
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LoadView assign(AssignDriverCommand command) {
        lockIdempotency(command.tenantId(), command.workspaceId(), command.actorMembershipId(), "DELIVERY_LOAD_ASSIGN",
                command.idempotencyKey());
        Optional<LoadView> replay = replay(command.tenantId(), command.workspaceId(), command.actorMembershipId(),
                "DELIVERY_LOAD_ASSIGN", command.idempotencyKey(), command.requestHash());
        if (replay.isPresent()) return replay.get();
        LoadHeader header = lockLoad(command.tenantId(), command.workspaceId(), command.loadId());
        checkVersion(header, command.expectedVersion());
        if (header.status() != DeliveryLoadStatus.DRAFT) throw error("FULFILLMENT_TRANSITION_INVALID", false);
        attachAssignedDriverToPlannedDeliveries(command);
        int updated = jdbc.update("update logistics.delivery_load set status='ASSIGNED',assigned_driver_membership_id=?,"
                        + "vehicle_reference=?,assigned_by_membership_id=?,assigned_at=?,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and id=? and version=?",
                command.driverMembershipId(), command.vehicleReference(), command.actorMembershipId(),
                Timestamp.from(command.occurredAt()), Timestamp.from(command.occurredAt()), command.tenantId(),
                command.workspaceId(), command.loadId(), command.expectedVersion());
        if (updated != 1) throw error("CONCURRENCY_CONFLICT", false);
        long eventNumber = header.version() + 2;
        insertEvent(command.tenantId(), command.workspaceId(), command.loadId(), eventNumber, "DRIVER_ASSIGNED",
                header.status(), DeliveryLoadStatus.ASSIGNED, command.actorMembershipId(),
                command.driverMembershipId(), command.vehicleReference(), null, List.of(), List.of(), null,
                command.occurredAt());
        LoadView result = load(command.tenantId(), command.workspaceId(), command.loadId());
        remember(command.tenantId(), command.workspaceId(), command.actorMembershipId(), "DELIVERY_LOAD_ASSIGN",
                command.idempotencyKey(), command.requestHash(), result, command.occurredAt());
        return result;
    }

    private void attachAssignedDriverToPlannedDeliveries(AssignDriverCommand command) {
        List<LoadStop> stops = jdbc.query("select fulfillment_id,delivery_id from logistics.delivery_load_stop "
                        + "where tenant_id=? and workspace_id=? and load_id=? order by position for update",
                (rs, row) -> new LoadStop(rs.getObject("fulfillment_id", UUID.class),
                        rs.getObject("delivery_id", UUID.class)),
                command.tenantId(), command.workspaceId(), command.loadId());
        if (stops.size() < 2 || stops.size() > 20) throw error("FULFILLMENT_TRANSITION_INVALID", false);
        for (LoadStop stop : stops) {
            List<CanonicalDriverAssignment> assignments = jdbc.query("select a.id,a.responsible_membership_id,"
                            + "a.responsible_user_id,a.actor_membership_id,a.assigned_at,d.status "
                            + "from logistics.fulfillment_driver_assignment a join logistics.delivery d "
                            + "on d.tenant_id=a.tenant_id and d.workspace_id=a.workspace_id and d.fulfillment_id=a.fulfillment_id "
                            + "where a.tenant_id=? and a.workspace_id=? and a.fulfillment_id=? and d.id=? for update of d",
                    (rs, row) -> new CanonicalDriverAssignment(rs.getObject("id", UUID.class),
                            rs.getObject("responsible_membership_id", UUID.class),
                            rs.getObject("responsible_user_id", UUID.class),
                            rs.getObject("actor_membership_id", UUID.class),
                            instant(rs, "assigned_at"), rs.getString("status")),
                    command.tenantId(), command.workspaceId(), stop.fulfillmentId(), stop.deliveryId());
            if (assignments.size() != 1 || !"PLANNED".equals(assignments.getFirst().deliveryStatus())
                    || !command.driverMembershipId().equals(assignments.getFirst().membershipId())) {
                throw error("FULFILLMENT_DRIVER_ASSIGNMENT_REQUIRED", false);
            }
            CanonicalDriverAssignment assignment = assignments.getFirst();
            int inserted = jdbc.update("insert into logistics.delivery_assignment(id,tenant_id,workspace_id,delivery_id,"
                            + "responsible_membership_id,operator_id,vehicle_reference,assigned_at,actor_membership_id,"
                            + "fulfillment_driver_assignment_id) values (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), command.tenantId(), command.workspaceId(), stop.deliveryId(),
                    assignment.membershipId(), assignment.userId(), command.vehicleReference(),
                    Timestamp.from(assignment.assignedAt()), assignment.actorMembershipId(), assignment.id());
            if (inserted != 1) throw error("FULFILLMENT_DRIVER_ASSIGNMENT_REQUIRED", false);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LoadView offer(LoadActionCommand command) {
        return transition(command, "DELIVERY_LOAD_OFFER", "OFFERED", SetStatus.ASSIGNED, "OFFERED", false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LoadView confirmHandoff(LoadActionCommand command) {
        return transition(command, "DELIVERY_LOAD_HANDOFF_CONFIRM", "HANDOFF_CONFIRMED",
                SetStatus.OFFERED_OR_ACCEPTED, "HANDOFF_CONFIRMED", true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LoadView accept(LoadActionCommand command) {
        return transition(command, "DELIVERY_LOAD_ACCEPT", "DRIVER_ACCEPTED",
                SetStatus.OFFERED_OR_CONFIRMED, "DRIVER_ACCEPTED", false);
    }

    private LoadView transition(LoadActionCommand command, String operation, String initialStatus,
                                SetStatus allowed, String eventType, boolean dispatchConfirmation) {
        lockIdempotency(command.tenantId(), command.workspaceId(), command.actorMembershipId(), operation,
                command.idempotencyKey());
        Optional<LoadView> replay = replay(command.tenantId(), command.workspaceId(), command.actorMembershipId(),
                operation, command.idempotencyKey(), command.requestHash());
        if (replay.isPresent()) {
            if ("DELIVERY_LOAD_ACCEPT".equals(operation)) {
                LoadHeader current = lockLoad(command.tenantId(), command.workspaceId(), command.loadId());
                requireAssignedDriver(current, command.actorMembershipId());
            }
            return replay.get();
        }
        LoadHeader header = lockLoad(command.tenantId(), command.workspaceId(), command.loadId());
        boolean acceptance = "DELIVERY_LOAD_ACCEPT".equals(operation);
        if (acceptance) requireAssignedDriver(header, command.actorMembershipId());
        if (acceptance || dispatchConfirmation) requireCurrentLoadDriverAssignments(command, header);
        checkVersion(header, command.expectedVersion());
        DeliveryLoadStatus next;
        if (dispatchConfirmation) {
            if (header.status() != DeliveryLoadStatus.OFFERED && header.status() != DeliveryLoadStatus.DRIVER_ACCEPTED) {
                throw error("FULFILLMENT_TRANSITION_INVALID", false);
            }
            next = header.driverAcceptedAt() == null
                    ? DeliveryLoadStatus.HANDOFF_CONFIRMED : DeliveryLoadStatus.RESPONSIBILITY_TRANSFERRED;
        } else if ("DELIVERY_LOAD_ACCEPT".equals(operation)) {
            if (header.status() != DeliveryLoadStatus.OFFERED && header.status() != DeliveryLoadStatus.HANDOFF_CONFIRMED) {
                throw error("FULFILLMENT_TRANSITION_INVALID", false);
            }
            next = header.dispatchConfirmedAt() == null
                    ? DeliveryLoadStatus.DRIVER_ACCEPTED : DeliveryLoadStatus.RESPONSIBILITY_TRANSFERRED;
        } else {
            if (header.status() != DeliveryLoadStatus.ASSIGNED) throw error("FULFILLMENT_TRANSITION_INVALID", false);
            next = DeliveryLoadStatus.OFFERED;
        }
        String setClause = dispatchConfirmation
                ? "dispatch_confirmed_by_membership_id=?,dispatch_confirmed_at=?"
                : "OFFERED".equals(eventType) ? "offered_by_membership_id=?,offered_at=?"
                : "driver_accepted_by_membership_id=?,driver_accepted_at=?";
        int updated = jdbc.update("update logistics.delivery_load set status=?," + setClause
                        + ",version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and version=?",
                next.name(), command.actorMembershipId(), Timestamp.from(command.occurredAt()),
                Timestamp.from(command.occurredAt()), command.tenantId(), command.workspaceId(), command.loadId(),
                command.expectedVersion());
        if (updated != 1) throw error("CONCURRENCY_CONFLICT", false);
        insertEvent(command.tenantId(), command.workspaceId(), command.loadId(), header.version() + 2,
                eventType, header.status(), next, command.actorMembershipId(), null, null, null, List.of(), List.of(),
                null, command.occurredAt());
        LoadView result = load(command.tenantId(), command.workspaceId(), command.loadId());
        remember(command.tenantId(), command.workspaceId(), command.actorMembershipId(), operation,
                command.idempotencyKey(), command.requestHash(), result, command.occurredAt());
        return result;
    }

    /** Recheck the load's Driver against both canonical Fulfillment and Delivery assignments under row locks. */
    private void requireCurrentLoadDriverAssignments(LoadActionCommand command, LoadHeader header) {
        UUID assignedDriver = header.assignedDriverMembershipId();
        if (assignedDriver == null) throw error("FULFILLMENT_DRIVER_ASSIGNMENT_STALE", false);
        List<CurrentLoadDriverAssignment> assignments = jdbc.query(
                "select s.fulfillment_id,s.delivery_id,f.version fulfillment_version,"
                        + "a.fulfillment_version assignment_fulfillment_version,a.responsible_membership_id fulfillment_driver_id,"
                        + "da.delivery_id assigned_delivery_id,da.responsible_membership_id delivery_driver_id "
                        + "from logistics.delivery_load_stop s "
                        + "join logistics.fulfillment f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id "
                        + "and f.id=s.fulfillment_id "
                        + "join logistics.delivery d on d.tenant_id=s.tenant_id and d.workspace_id=s.workspace_id "
                        + "and d.id=s.delivery_id and d.fulfillment_id=s.fulfillment_id "
                        + "left join lateral (select current_assignment.id,current_assignment.fulfillment_version,"
                        + "current_assignment.responsible_membership_id from logistics.fulfillment_driver_assignment current_assignment "
                        + "where current_assignment.tenant_id=f.tenant_id and current_assignment.workspace_id=f.workspace_id "
                        + "and current_assignment.fulfillment_id=f.id order by current_assignment.fulfillment_version desc,"
                        + "current_assignment.assigned_at desc,current_assignment.id desc limit 1) a on true "
                        + "left join logistics.delivery_assignment da on da.tenant_id=d.tenant_id "
                        + "and da.workspace_id=d.workspace_id and da.delivery_id=d.id "
                        + "and da.fulfillment_driver_assignment_id=a.id "
                        + "where s.tenant_id=? and s.workspace_id=? and s.load_id=? order by s.position for update of f,d",
                (rs, row) -> new CurrentLoadDriverAssignment(rs.getObject("fulfillment_id", UUID.class),
                        rs.getObject("delivery_id", UUID.class), rs.getLong("fulfillment_version"),
                        rs.getObject("assignment_fulfillment_version", Long.class),
                        rs.getObject("fulfillment_driver_id", UUID.class),
                        rs.getObject("assigned_delivery_id", UUID.class),
                        rs.getObject("delivery_driver_id", UUID.class)),
                command.tenantId(), command.workspaceId(), command.loadId());
        Integer stopCount = jdbc.queryForObject("select count(*) from logistics.delivery_load_stop "
                        + "where tenant_id=? and workspace_id=? and load_id=?",
                Integer.class, command.tenantId(), command.workspaceId(), command.loadId());
        if (stopCount == null || assignments.size() != stopCount
                || assignments.stream().anyMatch(assignment -> assignment.assignmentFulfillmentVersion() == null
                || assignment.fulfillmentVersion() != assignment.assignmentFulfillmentVersion() + 1
                || !assignedDriver.equals(assignment.fulfillmentDriverId())
                || !assignment.deliveryId().equals(assignment.assignedDeliveryId())
                || !assignedDriver.equals(assignment.deliveryDriverId()))) {
            throw error("FULFILLMENT_DRIVER_ASSIGNMENT_STALE", false);
        }
    }

    private List<DeliveryFacts> candidateFacts(UUID tenantId, UUID workspaceId, List<UUID> fulfillmentIds) {
        String sql = "select f.id fulfillment_id,f.version fulfillment_version,f.status fulfillment_status,"
                + "d.id delivery_id,coalesce(d.version,0) delivery_version,d.status delivery_status,"
                + "coalesce(d.dispatch_order_id,ord.id) selected_dispatch_order_id,ord.status dispatch_order_status,"
                + "case when ord.delivery_window_start is null and ord.delivery_window_end is null "
                + "then planned.window_start else ord.delivery_window_start end delivery_window_start,"
                + "case when ord.delivery_window_start is null and ord.delivery_window_end is null "
                + "then planned.window_end else ord.delivery_window_end end delivery_window_end,"
                + "ord.temperature_min,ord.temperature_max,"
                + "ord.temperature_unit,ord.temperature_status,"
                + "(f.status='HOLD' or exists(select 1 from logistics.fulfillment_outgoing_goods_check c "
                + "where c.tenant_id=f.tenant_id and c.workspace_id=f.workspace_id and c.fulfillment_id=f.id "
                + "and c.matches=false and not exists(select 1 from logistics.fulfillment_outgoing_discrepancy_resolution r "
                + "where r.tenant_id=c.tenant_id and r.workspace_id=c.workspace_id and r.discrepancy_check_id=c.id)) "
                + "or exists(select 1 from logistics.temperature_excursion x where x.tenant_id=f.tenant_id "
                + "and x.workspace_id=f.workspace_id and x.delivery_id=d.id and x.status in ('OPEN','HOLD'))) fulfillment_held,"
                + "(exists(select 1 from logistics.operational_exception_case c join lateral (select t.to_status "
                + "from logistics.operational_exception_transition t where t.tenant_id=c.tenant_id and t.workspace_id=c.workspace_id "
                + "and t.exception_id=c.id order by t.transition_number desc limit 1) current on true "
                + "where c.tenant_id=f.tenant_id and c.workspace_id=f.workspace_id and c.delivery_id=d.id "
                + "and c.severity in ('BLOCKING','CRITICAL') and current.to_status in ('OPEN','CLAIMED','UNDER_REVIEW')) "
                + "or exists(select 1 from logistics.driver_delivery_incident i where i.tenant_id=f.tenant_id "
                + "and i.workspace_id=f.workspace_id and i.delivery_id=d.id and i.exception_severity in ('BLOCKING','CRITICAL')) "
                + "or exists(select 1 from logistics.delivery_incident i where i.tenant_id=f.tenant_id "
                + "and i.workspace_id=f.workspace_id and i.dispatch_order_id=ord.id "
                + "and i.incident_type='TEMPERATURE_EXCURSION' and i.severity='CRITICAL')) blocking_incident,"
                + "(exists(select 1 from logistics.fulfillment_driver_assignment a where a.tenant_id=f.tenant_id "
                + "and a.workspace_id=f.workspace_id and a.fulfillment_id=f.id) or exists(select 1 "
                + "from logistics.delivery_assignment a join logistics.delivery assigned on assigned.tenant_id=a.tenant_id "
                + "and assigned.workspace_id=a.workspace_id and assigned.id=a.delivery_id "
                + "where assigned.tenant_id=f.tenant_id and assigned.workspace_id=f.workspace_id "
                + "and assigned.fulfillment_id=f.id)) existing_driver_assignment,"
                + "exists(select 1 from logistics.delivery_load_stop s where s.tenant_id=f.tenant_id "
                + "and s.workspace_id=f.workspace_id and s.fulfillment_id=f.id) existing_load "
                + "from logistics.fulfillment f left join logistics.delivery d on d.tenant_id=f.tenant_id "
                + "and d.workspace_id=f.workspace_id and d.fulfillment_id=f.id "
                + "left join lateral (select selected.id,selected.status,selected.delivery_window_start,selected.delivery_window_end,"
                + "selected.temperature_min,selected.temperature_max,selected.temperature_unit,selected.temperature_status "
                + "from logistics.dispatch_order selected where selected.tenant_id=f.tenant_id and selected.workspace_id=f.workspace_id "
                + "and ((d.dispatch_order_id is not null and selected.id=d.dispatch_order_id) "
                + "or (d.dispatch_order_id is null and selected.sales_order_id=f.sales_order_id)) "
                + "order by case when selected.id=d.dispatch_order_id then 0 else 1 end,selected.created_at desc,selected.id desc limit 1) ord on true "
                + "left join lateral (select p.window_start,p.window_end from logistics.fulfillment_dispatch_window_plan p "
                + "where p.tenant_id=f.tenant_id and p.workspace_id=f.workspace_id and p.fulfillment_id=f.id "
                + "order by p.revision desc limit 1) planned on true "
                + "where f.tenant_id=? and f.workspace_id=? and f.id in (" + in(fulfillmentIds.size()) + ") "
                + "order by f.id";
        return jdbc.query(sql, (rs, row) -> facts(rs), scoped(tenantId, workspaceId, fulfillmentIds).toArray());
    }

    private static DeliveryFacts facts(ResultSet rs) throws SQLException {
        UUID deliveryId = rs.getObject("delivery_id", UUID.class);
        return new DeliveryFacts(deliveryId, rs.getLong("delivery_version"),
                rs.getObject("fulfillment_id", UUID.class), rs.getLong("fulfillment_version"),
                rs.getString("fulfillment_status"), rs.getString("delivery_status"), null,
                instant(rs, "delivery_window_start"), instant(rs, "delivery_window_end"),
                rs.getBigDecimal("temperature_min"), rs.getBigDecimal("temperature_max"),
                rs.getString("temperature_unit"), rs.getString("temperature_status"),
                rs.getString("dispatch_order_status"), rs.getBoolean("fulfillment_held"),
                rs.getBoolean("blocking_incident"), false,
                rs.getBoolean("existing_driver_assignment"), rs.getBoolean("existing_load"));
    }

    private PlannedDelivery ensurePlannedDelivery(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                                  UUID knownDeliveryId, Instant now) {
        List<PlannedDelivery> existing = jdbc.query("select id,version,status from logistics.delivery where tenant_id=? "
                        + "and workspace_id=? and fulfillment_id=? for update",
                (rs, row) -> new PlannedDelivery(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status")),
                tenantId, workspaceId, fulfillmentId);
        if (!existing.isEmpty()) {
            PlannedDelivery delivery = existing.getFirst();
            if (!"PLANNED".equals(delivery.status()) || knownDeliveryId != null && !knownDeliveryId.equals(delivery.deliveryId())) {
                throw error("FULFILLMENT_TRANSITION_INVALID", false);
            }
            return copyCustomerInstructions(tenantId, workspaceId, fulfillmentId, delivery, now);
        }
        PlanningSource source = jdbc.query("select f.sales_order_id,f.destination_snapshot,ord.id dispatch_order_id "
                        + "from logistics.fulfillment f left join lateral (select d.id from logistics.dispatch_order d "
                        + "where d.tenant_id=f.tenant_id and d.workspace_id=f.workspace_id and d.sales_order_id=f.sales_order_id "
                        + "order by d.created_at desc,d.id desc limit 1) ord on true "
                        + "where f.tenant_id=? and f.workspace_id=? and f.id=?",
                (rs, row) -> new PlanningSource(rs.getObject("sales_order_id", UUID.class),
                        rs.getString("destination_snapshot"), rs.getObject("dispatch_order_id", UUID.class)),
                tenantId, workspaceId, fulfillmentId).stream().findFirst()
                .orElseThrow(() -> error("FULFILLMENT_NOT_FOUND", true));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("insert into logistics.delivery(id,tenant_id,workspace_id,fulfillment_id,dispatch_order_id,status,"
                        + "destination_snapshot,created_at,updated_at,version) values (?,?,?,?,?,'PLANNED',?,?,?,0)",
                deliveryId, tenantId, workspaceId, fulfillmentId, source.dispatchOrderId(), source.destinationSnapshot(),
                Timestamp.from(now), Timestamp.from(now));
        CustomerInstructionDeliveryProjection.copy(jdbc, tenantId, workspaceId, source.salesOrderId(), deliveryId, now);
        List<PlannedDelivery> created = jdbc.query("select id,version,status from logistics.delivery where tenant_id=? "
                        + "and workspace_id=? and id=?",
                (rs, row) -> new PlannedDelivery(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status")),
                tenantId, workspaceId, deliveryId);
        if (created.isEmpty()) throw error("DELIVERY_NOT_FOUND", true);
        return created.getFirst();
    }

    private PlannedDelivery copyCustomerInstructions(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                                      PlannedDelivery delivery, Instant now) {
        UUID salesOrderId = jdbc.query("select sales_order_id from logistics.fulfillment where tenant_id=? and workspace_id=? and id=?",
                        (rs, row) -> rs.getObject("sales_order_id", UUID.class), tenantId, workspaceId, fulfillmentId)
                .stream().findFirst().orElseThrow(() -> error("FULFILLMENT_NOT_FOUND", true));
        CustomerInstructionDeliveryProjection.copy(jdbc, tenantId, workspaceId, salesOrderId, delivery.deliveryId(), now);
        return jdbc.query("select id,version,status from logistics.delivery where tenant_id=? and workspace_id=? and id=?",
                        (rs, row) -> new PlannedDelivery(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status")),
                        tenantId, workspaceId, delivery.deliveryId())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_NOT_FOUND", true));
    }

    private List<LoadView> loadIds(String sql, UUID tenantId, UUID workspaceId, UUID driverMembershipId) {
        List<UUID> ids = driverMembershipId == null
                ? jdbc.query(sql, (rs, row) -> rs.getObject("id", UUID.class), tenantId, workspaceId)
                : jdbc.query(sql, (rs, row) -> rs.getObject("id", UUID.class), tenantId, workspaceId, driverMembershipId);
        return ids.stream().map(id -> load(tenantId, workspaceId, id)).toList();
    }

    private LoadView load(UUID tenantId, UUID workspaceId, UUID loadId) {
        List<LoadHeader> headers = jdbc.query("select id,version,status,origin_warehouse_id,assigned_driver_membership_id,"
                        + "vehicle_reference,capacity_sufficient,handling_compatible,zone_reasonable,no_exclusive_transport_restriction,"
                        + "attested_by_membership_id,attested_at,attestation_observation,offered_by_membership_id,offered_at,"
                        + "dispatch_confirmed_by_membership_id,dispatch_confirmed_at,driver_accepted_by_membership_id,driver_accepted_at "
                        + "from logistics.delivery_load where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new LoadHeader(rs.getObject("id", UUID.class), rs.getLong("version"),
                        status(rs.getString("status")), rs.getObject("origin_warehouse_id", UUID.class),
                        rs.getObject("assigned_driver_membership_id", UUID.class), rs.getString("vehicle_reference"),
                        rs.getBoolean("capacity_sufficient"), rs.getBoolean("handling_compatible"),
                        rs.getBoolean("zone_reasonable"), rs.getBoolean("no_exclusive_transport_restriction"),
                        rs.getObject("attested_by_membership_id", UUID.class), instant(rs, "attested_at"),
                        rs.getString("attestation_observation"), rs.getObject("offered_by_membership_id", UUID.class),
                        instant(rs, "offered_at"), rs.getObject("dispatch_confirmed_by_membership_id", UUID.class),
                        instant(rs, "dispatch_confirmed_at"), rs.getObject("driver_accepted_by_membership_id", UUID.class),
                        instant(rs, "driver_accepted_at")), tenantId, workspaceId, loadId);
        if (headers.isEmpty()) throw error("DELIVERY_NOT_FOUND", true);
        LoadHeader header = headers.getFirst();
        List<StopView> stops = jdbc.query("select s.fulfillment_id,s.delivery_id,s.position,d.version delivery_version "
                        + "from logistics.delivery_load_stop s join logistics.delivery d on d.tenant_id=s.tenant_id "
                        + "and d.workspace_id=s.workspace_id and d.id=s.delivery_id where s.tenant_id=? "
                        + "and s.workspace_id=? and s.load_id=? order by s.position",
                (rs, row) -> new StopView(rs.getObject("fulfillment_id", UUID.class),
                        rs.getObject("delivery_id", UUID.class), rs.getInt("position"), rs.getLong("delivery_version")),
                tenantId, workspaceId, loadId);
        List<HistoryEvent> history = jdbc.query("select event_type,actor_membership_id,affected_driver_membership_id,"
                        + "occurred_at,reason,previous_stop_order::text previous_order,new_stop_order::text new_order,"
                        + "capacity_sufficient,handling_compatible,zone_reasonable,no_exclusive_transport_restriction,"
                        + "attested_by_membership_id,attested_at,attestation_observation "
                        + "from logistics.delivery_load_event where tenant_id=? and workspace_id=? and load_id=? "
                        + "order by event_number",
                (rs, row) -> history(rs), tenantId, workspaceId, loadId);
        LoadCompatibilityAttestation attestation = new LoadCompatibilityAttestation(header.capacitySufficient(),
                header.handlingCompatible(), header.zoneReasonable(), header.noExclusiveTransportRestriction(),
                header.attestedByMembershipId(), header.attestedAt(), header.attestationObservation());
        return new LoadView(header.id(), header.version(), header.status(), header.originWarehouseId(), stops,
                header.assignedDriverMembershipId(), header.vehicleReference(),
                CompatibilityAttestationView.from(attestation), header.offeredByMembershipId(), header.offeredAt(),
                header.dispatchConfirmedByMembershipId(), header.dispatchConfirmedAt(),
                header.driverAcceptedByMembershipId(), header.driverAcceptedAt(), history);
    }

    private HistoryEvent history(ResultSet rs) throws SQLException {
        boolean attested = rs.getObject("attested_by_membership_id", UUID.class) != null;
        CompatibilityAttestationView attestation = attested
                ? new CompatibilityAttestationView(rs.getBoolean("capacity_sufficient"),
                rs.getBoolean("handling_compatible"), rs.getBoolean("zone_reasonable"),
                rs.getBoolean("no_exclusive_transport_restriction"), rs.getObject("attested_by_membership_id", UUID.class),
                instant(rs, "attested_at"), rs.getString("attestation_observation")) : null;
        return new HistoryEvent(rs.getString("event_type"), rs.getObject("actor_membership_id", UUID.class),
                instant(rs, "occurred_at"), rs.getString("reason"),
                rs.getObject("affected_driver_membership_id", UUID.class),
                readUuidList(rs.getString("previous_order")), readUuidList(rs.getString("new_order")), attestation);
    }

    private void insertEvent(UUID tenantId, UUID workspaceId, UUID loadId, long eventNumber, String eventType,
                             DeliveryLoadStatus previousStatus, DeliveryLoadStatus newStatus, UUID actorMembershipId,
                             UUID affectedDriverMembershipId, String vehicleReference, String reason,
                             List<UUID> previousOrder, List<UUID> newOrder,
                             LoadCompatibilityAttestation attestation, Instant occurredAt) {
        jdbc.update("insert into logistics.delivery_load_event(id,tenant_id,workspace_id,load_id,event_number,event_type,"
                        + "previous_status,new_status,actor_membership_id,affected_driver_membership_id,vehicle_reference,reason,"
                        + "previous_stop_order,new_stop_order,capacity_sufficient,handling_compatible,zone_reasonable,"
                        + "no_exclusive_transport_restriction,attested_by_membership_id,attested_at,attestation_observation,occurred_at) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, loadId, eventNumber, eventType,
                previousStatus == null ? null : previousStatus.name(), newStatus.name(), actorMembershipId,
                affectedDriverMembershipId, vehicleReference, reason, writeJson(previousOrder), writeJson(newOrder),
                attestation == null ? null : attestation.capacitySufficient(),
                attestation == null ? null : attestation.handlingCompatible(),
                attestation == null ? null : attestation.zoneReasonable(),
                attestation == null ? null : attestation.noExclusiveTransportRestriction(),
                attestation == null ? null : attestation.attestedByMembershipId(),
                attestation == null ? null : Timestamp.from(attestation.attestedAt()),
                attestation == null ? null : attestation.observation(), Timestamp.from(occurredAt));
    }

    private void updateVersion(UUID tenantId, UUID workspaceId, UUID loadId, long expected, long next, Instant now) {
        if (jdbc.update("update logistics.delivery_load set version=?,updated_at=? where tenant_id=? and workspace_id=? "
                        + "and id=? and version=?", next, Timestamp.from(now), tenantId, workspaceId, loadId, expected) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
    }

    private LoadHeader lockLoad(UUID tenantId, UUID workspaceId, UUID loadId) {
        List<LoadHeader> headers = jdbc.query("select id,version,status,origin_warehouse_id,assigned_driver_membership_id,"
                        + "vehicle_reference,capacity_sufficient,handling_compatible,zone_reasonable,no_exclusive_transport_restriction,"
                        + "attested_by_membership_id,attested_at,attestation_observation,offered_by_membership_id,offered_at,"
                        + "dispatch_confirmed_by_membership_id,dispatch_confirmed_at,driver_accepted_by_membership_id,driver_accepted_at "
                        + "from logistics.delivery_load where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> header(rs), tenantId, workspaceId, loadId);
        if (headers.isEmpty()) throw error("DELIVERY_NOT_FOUND", true);
        return headers.getFirst();
    }

    private LoadHeader header(ResultSet rs) throws SQLException {
        return new LoadHeader(rs.getObject("id", UUID.class), rs.getLong("version"), status(rs.getString("status")),
                rs.getObject("origin_warehouse_id", UUID.class), rs.getObject("assigned_driver_membership_id", UUID.class),
                rs.getString("vehicle_reference"), rs.getBoolean("capacity_sufficient"),
                rs.getBoolean("handling_compatible"), rs.getBoolean("zone_reasonable"),
                rs.getBoolean("no_exclusive_transport_restriction"), rs.getObject("attested_by_membership_id", UUID.class),
                instant(rs, "attested_at"), rs.getString("attestation_observation"),
                rs.getObject("offered_by_membership_id", UUID.class), instant(rs, "offered_at"),
                rs.getObject("dispatch_confirmed_by_membership_id", UUID.class), instant(rs, "dispatch_confirmed_at"),
                rs.getObject("driver_accepted_by_membership_id", UUID.class), instant(rs, "driver_accepted_at"));
    }

    private boolean terminalStopExists(UUID tenantId, UUID workspaceId, UUID loadId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.delivery_load_stop s "
                        + "join logistics.delivery d on d.tenant_id=s.tenant_id and d.workspace_id=s.workspace_id "
                        + "and d.id=s.delivery_id where s.tenant_id=? and s.workspace_id=? and s.load_id=? "
                        + "and d.status in ('DELIVERED','PARTIAL','FAILED','CANCELLED'))",
                Boolean.class, tenantId, workspaceId, loadId));
    }

    private void requireAssignedDriver(LoadHeader header, UUID actorMembershipId) {
        if (!Objects.equals(header.assignedDriverMembershipId(), actorMembershipId)) {
            throw error("DELIVERY_NOT_FOUND", true);
        }
    }

    private static void checkVersion(LoadHeader header, long expectedVersion) {
        if (header.version() != expectedVersion) throw error("CONCURRENCY_CONFLICT", false);
    }

    private void lockIdempotency(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenantId + ":" + workspaceId + ":" + actor + ":" + operation + ":" + key);
    }

    private Optional<LoadView> replay(UUID tenantId, UUID workspaceId, UUID actor, String operation,
                                      String key, String requestHash) {
        return findReplay(tenantId, workspaceId, actor, operation, key, requestHash);
    }

    private void remember(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key,
                          String requestHash, LoadView result, Instant now) {
        jdbc.update("insert into logistics.delivery_load_command_idempotency(tenant_id,workspace_id,actor_membership_id,"
                        + "operation,idempotency_key,request_hash,response_json,created_at) values (?,?,?,?,?,?,?,?)",
                tenantId, workspaceId, actor, operation, key, requestHash, writeJson(result), Timestamp.from(now));
    }

    private LoadView readView(String json) {
        try {
            return objectMapper.readValue(json, LoadView.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored Delivery load response cannot be read", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Delivery load response cannot be stored", exception);
        }
    }

    private List<UUID> readUuidList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored Delivery load order cannot be read", exception);
        }
    }

    private static void ensureHash(String prior, String requested) {
        if (!Objects.equals(prior, requested)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
    }

    private static DeliveryFacts withOrigin(DeliveryFacts fact, UUID origin) {
        return new DeliveryFacts(fact.deliveryId(), fact.deliveryVersion(), fact.fulfillmentId(),
                fact.fulfillmentVersion(), fact.fulfillmentStatus(), fact.deliveryStatus(), origin,
                fact.windowStart(), fact.windowEnd(), fact.temperatureMin(), fact.temperatureMax(),
                fact.temperatureUnit(), fact.temperatureStatus(), fact.dispatchOrderStatus(), fact.fulfillmentHeld(),
                fact.unresolvedBlockingOrCriticalIncident(), fact.structuredExclusiveTransportRestriction(),
                fact.hasExistingDriverAssignment(), fact.hasExistingLoad());
    }

    private static boolean matchesLockedFacts(DeliveryFacts current, DeliveryFacts evaluated) {
        return Objects.equals(current.fulfillmentId(), evaluated.fulfillmentId())
                && current.fulfillmentVersion() == evaluated.fulfillmentVersion()
                && Objects.equals(current.fulfillmentStatus(), evaluated.fulfillmentStatus())
                && Objects.equals(current.deliveryId(), evaluated.deliveryId())
                && current.deliveryVersion() == evaluated.deliveryVersion()
                && Objects.equals(current.deliveryStatus(), evaluated.deliveryStatus())
                && Objects.equals(current.windowStart(), evaluated.windowStart())
                && Objects.equals(current.windowEnd(), evaluated.windowEnd())
                && Objects.equals(current.temperatureStatus(), evaluated.temperatureStatus())
                && Objects.equals(current.dispatchOrderStatus(), evaluated.dispatchOrderStatus())
                && current.fulfillmentHeld() == evaluated.fulfillmentHeld()
                && current.unresolvedBlockingOrCriticalIncident() == evaluated.unresolvedBlockingOrCriticalIncident()
                && current.structuredExclusiveTransportRestriction() == evaluated.structuredExclusiveTransportRestriction()
                && current.hasExistingDriverAssignment() == evaluated.hasExistingDriverAssignment()
                && current.hasExistingLoad() == evaluated.hasExistingLoad();
    }

    private static DeliveryLoadStatus status(String value) {
        try {
            return DeliveryLoadStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw error("FULFILLMENT_TRANSITION_INVALID", false);
        }
    }

    private static String in(int count) {
        if (count < 1 || count > 20) throw error("INVALID_REQUEST", false);
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static List<Object> scoped(UUID tenantId, UUID workspaceId, List<UUID> ids) {
        List<Object> values = new ArrayList<>(List.of(tenantId, workspaceId));
        values.addAll(ids);
        return values;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }

    private enum SetStatus { ASSIGNED, OFFERED_OR_ACCEPTED, OFFERED_OR_CONFIRMED }

    private record IdempotencyRow(String requestHash, String responseJson) { }
    private record PlannedDelivery(UUID deliveryId, long version, String status) { }
    private record PlanningSource(UUID salesOrderId, String destinationSnapshot, UUID dispatchOrderId) { }
    private record LoadStop(UUID fulfillmentId, UUID deliveryId) { }
    private record CurrentLoadDriverAssignment(UUID fulfillmentId, UUID deliveryId, long fulfillmentVersion,
                                               Long assignmentFulfillmentVersion, UUID fulfillmentDriverId,
                                               UUID assignedDeliveryId, UUID deliveryDriverId) { }
    private record CanonicalDriverAssignment(UUID id, UUID membershipId, UUID userId, UUID actorMembershipId,
                                             Instant assignedAt, String deliveryStatus) { }
    private record LoadHeader(UUID id, long version, DeliveryLoadStatus status, UUID originWarehouseId,
                              UUID assignedDriverMembershipId, String vehicleReference,
                              boolean capacitySufficient, boolean handlingCompatible, boolean zoneReasonable,
                              boolean noExclusiveTransportRestriction, UUID attestedByMembershipId, Instant attestedAt,
                              String attestationObservation, UUID offeredByMembershipId, Instant offeredAt,
                              UUID dispatchConfirmedByMembershipId, Instant dispatchConfirmedAt,
                              UUID driverAcceptedByMembershipId, Instant driverAcceptedAt) { }
}
