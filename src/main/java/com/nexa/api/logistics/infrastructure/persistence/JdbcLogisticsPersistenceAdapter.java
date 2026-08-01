package com.nexa.api.logistics.infrastructure.persistence;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.LogisticsOperationsService.LogisticsException;
import com.nexa.api.logistics.application.port.LogisticsPersistencePort;
import com.nexa.api.logistics.application.port.DispatchRouteStartPort;
import com.nexa.api.logistics.domain.dispatchorder.DeliveryWindow;
import com.nexa.api.logistics.domain.dispatchorder.ClientAccountId;
import com.nexa.api.logistics.domain.dispatchorder.DispatchOrder;
import com.nexa.api.logistics.domain.dispatchorder.DispatchNumber;
import com.nexa.api.logistics.domain.dispatchorder.DispatchStatus;
import com.nexa.api.logistics.domain.dispatchorder.DestinationSnapshot;
import com.nexa.api.logistics.domain.dispatchorder.InventoryReservationId;
import com.nexa.api.logistics.domain.dispatchorder.SalesOrderId;
import com.nexa.api.logistics.domain.dispatchorder.TransportAssignment;
import com.nexa.api.logistics.domain.incident.DeliveryIncident;
import com.nexa.api.logistics.domain.incident.IncidentSeverity;
import com.nexa.api.logistics.domain.incident.IncidentType;
import com.nexa.api.logistics.domain.proofofdelivery.ProofOfDeliveryRecord;
import com.nexa.api.logistics.domain.proofofdelivery.ProofOfDeliveryStatus;
import com.nexa.api.logistics.domain.temperaturereading.TemperatureReading;
import com.nexa.api.logistics.domain.temperaturereading.TemperatureReadingStatus;
import com.nexa.api.logistics.domain.temperaturereading.TemperatureScale;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcLogisticsPersistenceAdapter implements LogisticsPersistencePort, DispatchRouteStartPort {
    private static final int MAX_PAGE_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final ChangeEventPersistencePort changeFeed;
    private final WarehouseLogisticsFulfillmentPort warehouseFulfillment;

    public JdbcLogisticsPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                           WarehouseLogisticsFulfillmentPort warehouseFulfillment) {
        this.jdbc = jdbc; this.changeFeed = changeFeed; this.warehouseFulfillment = warehouseFulfillment;
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsService.Page<LogisticsOperationsService.DispatchView> list(String tenantId, String workspaceId, String clientAccountId, String status, int page, int size, String sort) {
        pageCheck(page, size); UUID tenant = uuid(tenantId), workspace = uuid(workspaceId); List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        String where = " where d.tenant_id=? and d.workspace_id=?";
        if (clientAccountId != null) { where += " and d.client_account_id=?"; args.add(uuid(clientAccountId)); }
        if (status != null && !status.isBlank()) { where += " and d.status=?"; args.add(enumValue(status, "status", DispatchStatus.values())); }
        long total = jdbc.queryForObject("select count(*) from logistics.dispatch_order d" + where, Long.class, args.toArray());
        String order = sort(sort, "updatedAt", "d.updated_at desc,d.id desc", "dispatchNumber", "d.dispatch_number asc,d.id asc", "deliveryWindowStart", "d.delivery_window_start asc nulls last,d.id asc", "status", "d.status asc,d.id asc");
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        List<LogisticsOperationsService.DispatchView> items = jdbc.query(selectSql() + where + " order by " + order + " limit ? offset ?", (rs, row) -> view(read(rs), clientAccountId != null), pageArgs.toArray());
        return new LogisticsOperationsService.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsService.DispatchView detail(String tenantId, String workspaceId, String clientAccountId, String dispatchId) {
        DispatchRow row = load(uuid(tenantId), uuid(workspaceId), uuid(dispatchId), clientAccountId == null ? null : uuid(clientAccountId), false);
        if (row == null) throw error("RESOURCE_NOT_FOUND", true);
        return view(row, clientAccountId != null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LogisticsOperationsService.DispatchEventView> events(String tenantId, String workspaceId, String clientAccountId, String dispatchId) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), dispatch = uuid(dispatchId);
        if (load(tenant, workspace, dispatch, clientAccountId == null ? null : uuid(clientAccountId), false) == null) throw error("RESOURCE_NOT_FOUND", true);
        String visibility = clientAccountId == null ? "" : " and buyer_visible=true";
        return jdbc.query("select id,event_type,from_status,to_status,occurred_at,buyer_visible from logistics.dispatch_event where tenant_id=? and workspace_id=? and dispatch_order_id=?" + visibility + " order by occurred_at,id",
                (rs, row) -> event(rs, clientAccountId != null), tenant, workspace, dispatch);
    }

    @Override
    @Transactional
    public LogisticsOperationsService.DispatchView create(String tenantId, String workspaceId, String reservationId, long reservationVersion, String actorMembershipId, String key, long now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), reservation = uuid(reservationId), actor = uuid(actorMembershipId); String hash = hash("dispatch-create", reservationId, reservationVersion);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-create", key, hash); if (replay != null) return replay;
        var source = warehouseFulfillment.loadReservedReservation(tenantId, workspaceId, reservationId, reservationVersion, Instant.ofEpochMilli(now));
        if (exists("select 1 from logistics.dispatch_order where tenant_id=? and workspace_id=? and inventory_reservation_id=?", tenant, workspace, reservation)) throw error("DISPATCH_ALREADY_EXISTS", false);
        UUID id = UUID.randomUUID(); int year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).getYear(); long sequence = nextDispatchNumber(tenant, workspace, year); DispatchNumber number = new DispatchNumber(String.format("DO-%04d-%06d", year, sequence));
        jdbc.update("insert into logistics.dispatch_order(id,tenant_id,workspace_id,dispatch_number,inventory_reservation_id,sales_order_id,client_account_id,status,destination_snapshot,temperature_min,temperature_max,temperature_unit,temperature_status,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                id, tenant, workspace, number.value(), reservation, source.salesOrderId(), source.clientAccountId(), "READY_FOR_OPERATIONS", source.destinationSnapshot(), source.temperatureMin(), source.temperatureMax(), source.temperatureUnit(), source.temperatureStatus(), timestamp(now), timestamp(now));
        appendEvent(tenant, workspace, id, "logistics.dispatch.created", null, "READY_FOR_OPERATIONS", actor, false, null, now, source.clientAccountId());
        saveIdempotency(tenant, workspace, "dispatch-create", key, hash, id, now);
        return detail(tenantId, workspaceId, null, id.toString());
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView prepare(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, long now) {
        return statusCommand(tenantId, workspaceId, dispatchId, version, actorMembershipId, key, "dispatch-preparation", "logistics.dispatch.preparation-started", now, "PREPARE", null);
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView assign(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String responsibleMembershipId, String vehicleReference, String routeName, long now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), id = uuid(dispatchId), actor = uuid(actorMembershipId), membership = uuid(responsibleMembershipId); String hash = hash("dispatch-assignment", dispatchId, version, responsibleMembershipId, vehicleReference, routeName);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-assignment", key, hash); if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null); if (row == null) throw error("RESOURCE_NOT_FOUND", true); if (row.version() != version) throw error("CONCURRENCY_CONFLICT", false); String display = jdbc.query("select u.display_name from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id join iam.user_account u on u.id=m.user_id where m.id=? and w.tenant_id=? and w.id=? and m.role='LOGISTICS' and m.status='ACTIVE'", rs -> rs.next() ? rs.getString(1) : null, membership, tenant, workspace); if (display == null) throw error("RESPONSIBLE_MEMBERSHIP_INVALID", false);
        DispatchOrder aggregate = aggregate(row); aggregate.assign(new TransportAssignment(membership, display, vehicleReference, routeName)); updateAssignment(tenant, workspace, row, aggregate, membership, display, vehicleReference, routeName, now, actor, "logistics.dispatch.assigned", false, null); saveIdempotency(tenant, workspace, "dispatch-assignment", key, hash, id, now); return detail(tenantId, workspaceId, null, dispatchId);
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView schedule(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, Instant startsAt, Instant endsAt, Instant eta, long now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), id = uuid(dispatchId), actor = uuid(actorMembershipId); String hash = hash("dispatch-schedule", dispatchId, version, startsAt, endsAt, eta); LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-schedule", key, hash); if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null); if (row == null) throw error("RESOURCE_NOT_FOUND", true); if (row.version() != version) throw error("CONCURRENCY_CONFLICT", false); DispatchOrder aggregate = aggregate(row); aggregate.schedule(new DeliveryWindow(startsAt, endsAt), eta); updateSchedule(tenant, workspace, row, aggregate, startsAt, endsAt, eta, now, actor, "logistics.dispatch.scheduled", true, null); saveIdempotency(tenant, workspace, "dispatch-schedule", key, hash, id, now); return detail(tenantId, workspaceId, null, dispatchId);
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView ready(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, long now) {
        return statusCommand(tenantId, workspaceId, dispatchId, version, actorMembershipId, key, "dispatch-ready", "logistics.dispatch.ready", now, "READY", null);
    }

    @Override
    public java.util.Optional<LogisticsOperationsService.DispatchView> replayRouteStart(
            String tenantId, String workspaceId, String idempotencyKey, String requestHash) {
        LogisticsOperationsService.DispatchView value = replay(
                uuid(tenantId), uuid(workspaceId), "dispatch-route-start", idempotencyKey, requestHash);
        return java.util.Optional.ofNullable(value);
    }

    @Override
    public java.util.Optional<DispatchOrder> findDispatchForRouteStart(
            String tenantId, String workspaceId, String dispatchId) {
        DispatchRow row = locked(uuid(tenantId), uuid(workspaceId), uuid(dispatchId), null);
        return row == null ? java.util.Optional.empty() : java.util.Optional.of(aggregate(row));
    }

    @Override
    public LogisticsOperationsService.DispatchView commitRouteStart(
            String tenantId, String workspaceId, DispatchOrder aggregate,
            DispatchStatus expectedStatus, long expectedVersion,
            String actorMembershipId, String idempotencyKey, String requestHash, long nowEpochMillis) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), dispatchId = aggregate.id();
        UUID actor = uuid(actorMembershipId);
        DispatchRow row = locked(tenant, workspace, dispatchId, null);
        if (row == null) throw error("RESOURCE_NOT_FOUND", true);
        if (row.version() != expectedVersion || !expectedStatus.name().equals(row.status())) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        int changed = jdbc.update("update logistics.dispatch_order set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status=? and version=?",
                aggregate.status().name(), timestamp(nowEpochMillis), tenant, workspace, dispatchId,
                expectedStatus.name(), expectedVersion);
        if (changed != 1) throw error("CONCURRENCY_CONFLICT", false);
        appendEvent(tenant, workspace, dispatchId, "logistics.dispatch.route-started",
                expectedStatus.name(), aggregate.status().name(), actor, true, null, nowEpochMillis, row.clientAccountId());
        saveIdempotency(tenant, workspace, "dispatch-route-start", idempotencyKey, requestHash, dispatchId, nowEpochMillis);
        return detail(tenant.toString(), workspace.toString(), null, dispatchId.toString());
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView temperature(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, BigDecimal value, String unit, Instant recordedAt, String source, long now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), id = uuid(dispatchId), actor = uuid(actorMembershipId); String hash = hash("dispatch-temperature", dispatchId, version, value, unit, recordedAt, source); LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-temperature", key, hash); if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null); if (row == null) throw error("RESOURCE_NOT_FOUND", true); if (row.version() != version) throw error("CONCURRENCY_CONFLICT", false); if (!(row.status().equals("READY_FOR_ROUTE") || row.status().equals("IN_ROUTE") || row.status().equals("INCIDENT"))) throw error("INVALID_TRANSITION", false);
        TemperatureScale scale = TemperatureScale.from(unit); TemperaturePolicy policy = new TemperaturePolicy(row.temperatureMin(), row.temperatureMax(), row.temperatureUnit(), row.temperatureStatus()); BigDecimal celsius = scale.toCelsius(value); TemperatureReadingStatus readingStatus = policy.status(celsius); TemperatureReading reading = new TemperatureReading(value, scale, recordedAt, source == null || source.isBlank() ? "MANUAL" : source, readingStatus);
        jdbc.update("insert into logistics.temperature_reading(id,tenant_id,workspace_id,dispatch_order_id,value,unit,recorded_at,source,status,created_at) values (?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), tenant, workspace, id, reading.value(), reading.scale().name(), timestamp(reading.recordedAt().toEpochMilli()), reading.source(), reading.status().name(), timestamp(now));
        boolean excursion = readingStatus == TemperatureReadingStatus.OUT_OF_RANGE && row.status().equals("IN_ROUTE");
        if (excursion) {
            if (jdbc.update("update logistics.dispatch_order set status='INCIDENT',temperature_status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", readingStatus.name(), timestamp(now), tenant, workspace, id, row.version()) != 1) throw error("CONCURRENCY_CONFLICT", false);
            jdbc.update("insert into logistics.delivery_incident(id,tenant_id,workspace_id,dispatch_order_id,incident_type,severity,buyer_visible,description,occurred_at,created_at) values (?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), tenant, workspace, id, "TEMPERATURE_EXCURSION", "HIGH", false, "Temperature reading is outside the configured dispatch range", timestamp(reading.recordedAt().toEpochMilli()), timestamp(now));
            appendEvent(tenant, workspace, id, "logistics.dispatch.buyer-temperature-review", row.status(), "INCIDENT", actor, true, "Delivery review required", now, row.clientAccountId());
        } else {
            if (jdbc.update("update logistics.dispatch_order set temperature_status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", readingStatus.name(), timestamp(now), tenant, workspace, id, row.version()) != 1) throw error("CONCURRENCY_CONFLICT", false);
        }
        appendEvent(tenant, workspace, id, "logistics.dispatch.temperature-recorded", row.status(), excursion ? "INCIDENT" : row.status(), actor, false, null, now, row.clientAccountId()); saveIdempotency(tenant, workspace, "dispatch-temperature", key, hash, id, now); return detail(tenantId, workspaceId, null, dispatchId);
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView incident(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String type, String severity, boolean buyerVisible, String description, Instant occurredAt, String resolution, long now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), id = uuid(dispatchId), actor = uuid(actorMembershipId); String hash = hash("dispatch-incident", dispatchId, version, type, severity, buyerVisible, description, occurredAt, resolution); LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-incident", key, hash); if (replay != null) return replay;
        IncidentType incidentType = IncidentType.valueOf(enumValue(type, "incidentType", IncidentType.values())); IncidentSeverity incidentSeverity = IncidentSeverity.valueOf(enumValue(severity, "severity", IncidentSeverity.values())); DeliveryIncident incident = new DeliveryIncident(incidentType, incidentSeverity, buyerVisible, description, occurredAt, resolution); DispatchRow row = locked(tenant, workspace, id, null); if (row == null) throw error("RESOURCE_NOT_FOUND", true); if (row.version() != version) throw error("CONCURRENCY_CONFLICT", false); DispatchOrder aggregate = aggregate(row); String toStatus = row.status(); if (!row.status().equals("INCIDENT")) { aggregate.recordIncident(); toStatus = "INCIDENT"; updateStatus(tenant, workspace, row, aggregate, now, actor, "logistics.dispatch.incident-recorded", buyerVisible, description); } else { touch(tenant, workspace, row, now); appendEvent(tenant, workspace, id, "logistics.dispatch.incident-recorded", row.status(), row.status(), actor, buyerVisible, description, now, row.clientAccountId()); }
        jdbc.update("insert into logistics.delivery_incident(id,tenant_id,workspace_id,dispatch_order_id,incident_type,severity,buyer_visible,description,occurred_at,resolution,created_at) values (?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), tenant, workspace, id, incident.type().name(), incident.severity().name(), incident.buyerVisible(), incident.description(), timestamp(incident.occurredAt().toEpochMilli()), incident.resolution(), timestamp(now)); saveIdempotency(tenant, workspace, "dispatch-incident", key, hash, id, now); return detail(tenantId, workspaceId, null, dispatchId);
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView reprogram(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, Instant startsAt, Instant endsAt, Instant eta, String reason, long now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), id = uuid(dispatchId), actor = uuid(actorMembershipId); String hash = hash("dispatch-reprogram", dispatchId, version, startsAt, endsAt, eta, reason); LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-reprogram", key, hash); if (replay != null) return replay; DispatchRow row = locked(tenant, workspace, id, null); if (row == null) throw error("RESOURCE_NOT_FOUND", true); if (row.version() != version) throw error("CONCURRENCY_CONFLICT", false); DispatchOrder aggregate = aggregate(row); aggregate.reprogram(new DeliveryWindow(startsAt, endsAt), eta); updateSchedule(tenant, workspace, row, aggregate, startsAt, endsAt, eta, now, actor, "logistics.dispatch.reprogrammed", true, reason); saveIdempotency(tenant, workspace, "dispatch-reprogram", key, hash, id, now); return detail(tenantId, workspaceId, null, dispatchId);
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView cancel(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String reason, long now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), id = uuid(dispatchId), actor = uuid(actorMembershipId); String hash = hash("dispatch-cancel", dispatchId, version, reason); LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-cancel", key, hash); if (replay != null) return replay; DispatchRow row = locked(tenant, workspace, id, null); if (row == null) throw error("RESOURCE_NOT_FOUND", true); if (row.version() != version) throw error("CONCURRENCY_CONFLICT", false); DispatchOrder aggregate = aggregate(row); aggregate.cancel(); warehouseFulfillment.releaseReservation(tenantId, workspaceId, row.reservationId().toString(), actorMembershipId, id.toString(), reason, Instant.ofEpochMilli(now)); updateStatus(tenant, workspace, row, aggregate, now, actor, "logistics.dispatch.cancelled", true, reason); saveIdempotency(tenant, workspace, "dispatch-cancel", key, hash, id, now); return detail(tenantId, workspaceId, null, dispatchId);
    }

    @Override @Transactional
    public LogisticsOperationsService.DispatchView complete(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String receiverName, Instant completedAt, String notes, boolean photoDeclared, boolean signatureDeclared, long now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), id = uuid(dispatchId), actor = uuid(actorMembershipId); String hash = hash("dispatch-delivery", dispatchId, version, receiverName, completedAt, notes, photoDeclared, signatureDeclared); LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-delivery", key, hash); if (replay != null) return replay; DispatchRow row = locked(tenant, workspace, id, null); if (row == null) throw error("RESOURCE_NOT_FOUND", true); if (row.version() != version) throw error("CONCURRENCY_CONFLICT", false); DispatchOrder aggregate = aggregate(row); aggregate.deliver(); ProofOfDeliveryRecord pod = new ProofOfDeliveryRecord(receiverName, completedAt, notes, photoDeclared, signatureDeclared, ProofOfDeliveryStatus.COMPLETED); jdbc.update("insert into logistics.proof_of_delivery(id,tenant_id,workspace_id,dispatch_order_id,receiver_name,completed_at,notes,photo_evidence_declared,signature_evidence_declared,status,created_at) values (?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), tenant, workspace, id, pod.receiverName(), timestamp(pod.completedAt().toEpochMilli()), pod.notes(), pod.photoEvidenceDeclared(), pod.signatureEvidenceDeclared(), pod.status().name(), timestamp(now)); updateStatus(tenant, workspace, row, aggregate, now, actor, "logistics.dispatch.delivered", true, null); appendEvent(tenant, workspace, id, "logistics.pod.completed", row.status(), "DELIVERED", actor, true, null, now, row.clientAccountId()); saveIdempotency(tenant, workspace, "dispatch-delivery", key, hash, id, now); return detail(tenantId, workspaceId, null, dispatchId);
    }

    @Override @Transactional(readOnly = true)
    public LogisticsOperationsService.DashboardView dashboard(String tenantId, String workspaceId) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId); Long[] values = jdbc.queryForObject("select count(*) filter (where status='READY_FOR_OPERATIONS'),count(*) filter (where status='PREPARING'),count(*) filter (where status='ASSIGNED'),count(*) filter (where status='SCHEDULED'),count(*) filter (where status='READY_FOR_ROUTE'),count(*) filter (where status='IN_ROUTE'),count(*) filter (where status='INCIDENT'),count(*) filter (where status='DELIVERED' and updated_at>=current_date),count(*) filter (where temperature_status='OUT_OF_RANGE'),count(*) filter (where status='IN_ROUTE' and not exists(select 1 from logistics.proof_of_delivery p where p.dispatch_order_id=d.id)),0::bigint from logistics.dispatch_order d where tenant_id=? and workspace_id=?", (rs, row) -> { Long[] v = new Long[11]; for (int i=0;i<11;i++) v[i]=rs.getLong(i+1); return v; }, tenant, workspace); long reservations = warehouseFulfillment.countReadyReservations(tenantId, workspaceId, Instant.now()); return new LogisticsOperationsService.DashboardView(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], reservations);
    }

    @Override @Transactional(readOnly = true)
    public LogisticsOperationsService.AnalyticsView analytics(String tenantId, String workspaceId, Instant from, Instant to) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId);
        String sql = "select count(*), "
                + "count(*) filter(where status='DELIVERED'), "
                + "count(*) filter(where status='INCIDENT'), "
                + "(select count(*) from logistics.temperature_reading t where t.tenant_id=? and t.workspace_id=? and t.recorded_at>=? and t.recorded_at<? and t.status='OUT_OF_RANGE'), "
                + "(select count(*) from logistics.proof_of_delivery p where p.tenant_id=? and p.workspace_id=? and p.created_at>=? and p.created_at<?), "
                + "count(*) filter(where status='DELIVERED' and delivery_window_end is not null and updated_at<=delivery_window_end) "
                + "from logistics.dispatch_order d where d.tenant_id=? and d.workspace_id=? and d.created_at>=? and d.created_at<?";
        Long[] values = jdbc.queryForObject(sql, (rs, row) -> {
            Long[] result = new Long[6];
            for (int i = 0; i < result.length; i++) result[i] = rs.getLong(i + 1);
            return result;
        }, tenant, workspace, timestamp(from.toEpochMilli()), timestamp(to.toEpochMilli()),
                tenant, workspace, timestamp(from.toEpochMilli()), timestamp(to.toEpochMilli()),
                tenant, workspace, timestamp(from.toEpochMilli()), timestamp(to.toEpochMilli()));
        double onTime = values[1] == 0 ? 0d : (double) values[5] / values[1];
        double preparation = averageMinutes(tenant, workspace,
                "logistics.dispatch.created", "logistics.dispatch.preparation-started", from, to);
        double route = averageMinutes(tenant, workspace,
                "logistics.dispatch.route-started", "logistics.dispatch.delivered", from, to);
        return new LogisticsOperationsService.AnalyticsView(from, to, values[0], values[1], values[2], values[3], values[4], onTime, preparation, route);
    }

    @Override @Transactional(readOnly = true)
    public LogisticsOperationsService.Page<LogisticsOperationsService.ProofOfDeliveryView> proofOfDelivery(String tenantId, String workspaceId, String status, int page, int size) {
        pageCheck(page, size);
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId);
        List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        String from = " from logistics.dispatch_order d left join logistics.proof_of_delivery p on p.tenant_id=d.tenant_id and p.workspace_id=d.workspace_id and p.dispatch_order_id=d.id";
        String where = " where d.tenant_id=? and d.workspace_id=?";
        if (status != null && !status.isBlank()) {
            String normalized = enumValue(status, "status", new ProofOfDeliveryStatus[]{ProofOfDeliveryStatus.PENDING, ProofOfDeliveryStatus.COMPLETED});
            if ("PENDING".equals(normalized)) where += " and p.id is null and d.status not in ('DELIVERED','CANCELLED')";
            else { where += " and p.status=?"; args.add(normalized); }
        }
        long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        String sql = "select p.id,d.id,d.dispatch_number,case when p.id is null then 'PENDING' else p.status end,p.receiver_name,p.completed_at,p.notes,coalesce(p.photo_evidence_declared,false),coalesce(p.signature_evidence_declared,false),d.updated_at" + from + where + " order by d.updated_at desc,d.id desc limit ? offset ?";
        List<LogisticsOperationsService.ProofOfDeliveryView> items = jdbc.query(sql, (rs, row) -> new LogisticsOperationsService.ProofOfDeliveryView(
                rs.getObject(1) == null ? null : rs.getObject(1).toString(), rs.getObject(2).toString(), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7),
                rs.getBoolean(8), rs.getBoolean(9), rs.getTimestamp(10).toInstant()), pageArgs.toArray());
        return new LogisticsOperationsService.Page<>(items, page, size, total);
    }

    private LogisticsOperationsService.DispatchView statusCommand(String tenantId, String workspaceId, String dispatchId, long version, String actorMembershipId, String key, String operation, String eventType, long now, String command, String reason) {
        UUID tenant=uuid(tenantId), workspace=uuid(workspaceId), id=uuid(dispatchId), actor=uuid(actorMembershipId); String hash=hash(operation, dispatchId, version, reason); LogisticsOperationsService.DispatchView replay=replay(tenant,workspace,operation,key,hash); if(replay!=null)return replay; DispatchRow row=locked(tenant,workspace,id,null); if(row==null)throw error("RESOURCE_NOT_FOUND",true); if(row.version()!=version)throw error("CONCURRENCY_CONFLICT",false); DispatchOrder aggregate=aggregate(row); switch(command){case "PREPARE"->aggregate.startPreparation();case "READY"->{warehouseFulfillment.ensureReservationReady(tenantId,workspaceId,row.reservationId().toString(),Instant.ofEpochMilli(now)); aggregate.markReadyForRoute();}default->throw error("INVALID_REQUEST",false);} updateStatus(tenant,workspace,row,aggregate,now,actor,eventType,false,reason); saveIdempotency(tenant,workspace,operation,key,hash,id,now); return detail(tenantId,workspaceId,null,dispatchId);
    }

    private void updateStatus(UUID tenant, UUID workspace, DispatchRow row, DispatchOrder aggregate, long now, UUID actor, String eventType, boolean buyerVisible, String reason) { if (row.version() < 0) throw error("CONCURRENCY_CONFLICT", false); int changed=jdbc.update("update logistics.dispatch_order set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",aggregate.status().name(),timestamp(now),tenant,workspace,row.id(),row.version()); if(changed!=1)throw error("CONCURRENCY_CONFLICT",false); appendEvent(tenant,workspace,row.id(),eventType,row.status(),aggregate.status().name(),actor,buyerVisible,reason,now,row.clientAccountId()); }
    private void updateAssignment(UUID tenant, UUID workspace, DispatchRow row, DispatchOrder aggregate, UUID membership, String display, String vehicle, String route, long now, UUID actor, String eventType, boolean buyerVisible, String reason) { int changed=jdbc.update("update logistics.dispatch_order set status=?,responsible_membership_id=?,responsible_display_name_snapshot=?,vehicle_reference=?,route_name=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",aggregate.status().name(),membership,display,vehicle,route,timestamp(now),tenant,workspace,row.id(),row.version()); if(changed!=1)throw error("CONCURRENCY_CONFLICT",false); appendEvent(tenant,workspace,row.id(),eventType,row.status(),aggregate.status().name(),actor,buyerVisible,reason,now,row.clientAccountId()); }
    private void updateSchedule(UUID tenant, UUID workspace, DispatchRow row, DispatchOrder aggregate, Instant start, Instant end, Instant eta, long now, UUID actor, String eventType, boolean buyerVisible, String reason) { int changed=jdbc.update("update logistics.dispatch_order set status=?,delivery_window_start=?,delivery_window_end=?,eta=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",aggregate.status().name(),timestamp(start.toEpochMilli()),timestamp(end.toEpochMilli()),eta==null?null:timestamp(eta.toEpochMilli()),timestamp(now),tenant,workspace,row.id(),row.version()); if(changed!=1)throw error("CONCURRENCY_CONFLICT",false); appendEvent(tenant,workspace,row.id(),eventType,row.status(),aggregate.status().name(),actor,buyerVisible,reason,now,row.clientAccountId()); }
    private void touch(UUID tenant, UUID workspace, DispatchRow row, long now) { if(jdbc.update("update logistics.dispatch_order set updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",timestamp(now),tenant,workspace,row.id(),row.version())!=1)throw error("CONCURRENCY_CONFLICT",false); }


    private void appendEvent(UUID tenant, UUID workspace, UUID id, String eventType, String from, String to, UUID actor, boolean buyerVisible, String reason, long now, UUID client) { jdbc.update("insert into logistics.dispatch_event(id,tenant_id,workspace_id,dispatch_order_id,event_type,from_status,to_status,actor_membership_id,buyer_visible,reason,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),tenant,workspace,id,eventType,from,to,actor,buyerVisible,reason,timestamp(now)); String publicStatus=to==null?"ACTIVE":to; boolean explicit=buyerVisible; changeFeed.append(tenant.toString(),workspace.toString(),client==null?null:client.toString(),"dispatch_order",id.toString(),eventType,publicStatus,now,explicit); }

    private DispatchOrder aggregate(DispatchRow row) { return DispatchOrder.rehydrate(row.id(),new DispatchNumber(row.dispatchNumber()),new InventoryReservationId(row.reservationId()),new SalesOrderId(row.salesOrderId()),new ClientAccountId(row.clientAccountId()),new DestinationSnapshot(row.destination()),DispatchStatus.valueOf(row.status()),row.responsibleMembershipId()==null?null:new TransportAssignment(row.responsibleMembershipId(),row.responsibleDisplayName(),row.vehicleReference(),row.routeName()),row.windowStart()==null?null:new DeliveryWindow(row.windowStart(),row.windowEnd()),row.eta(),row.version()); }
    private DispatchRow locked(UUID tenant, UUID workspace, UUID id, UUID client) { return load(tenant,workspace,id,client,true); }
    private DispatchRow load(UUID tenant, UUID workspace, UUID id, UUID client, boolean lock) { String sql=selectSql()+" where d.tenant_id=? and d.workspace_id=? and d.id=?"; List<Object> args=new ArrayList<>(List.of(tenant,workspace,id)); if(client!=null){sql+=" and d.client_account_id=?";args.add(client);} if(lock)sql+=" for update of d"; return jdbc.query(sql,rs->rs.next()?read(rs):null,args.toArray()); }
    private String selectSql() { return "select d.id,d.dispatch_number,d.inventory_reservation_id,d.sales_order_id,d.client_account_id,d.status,d.destination_snapshot,d.delivery_window_start,d.delivery_window_end,d.eta,d.responsible_membership_id,d.responsible_display_name_snapshot,d.vehicle_reference,d.route_name,d.temperature_min,d.temperature_max,d.temperature_unit,d.temperature_status,d.version,d.updated_at,p.id,p.status,o.number from logistics.dispatch_order d join sales.sales_order o on o.tenant_id=d.tenant_id and o.workspace_id=d.workspace_id and o.id=d.sales_order_id left join logistics.proof_of_delivery p on p.tenant_id=d.tenant_id and p.workspace_id=d.workspace_id and p.dispatch_order_id=d.id"; }
    private DispatchRow read(ResultSet rs) throws java.sql.SQLException { return new DispatchRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7),instant(rs,8),instant(rs,9),instant(rs,10),rs.getObject(11,UUID.class),rs.getString(12),rs.getString(13),rs.getString(14),rs.getBigDecimal(15),rs.getBigDecimal(16),rs.getString(17),rs.getString(18),rs.getLong(19),instant(rs,20),rs.getString(23),rs.getObject(21,UUID.class)==null?null:rs.getObject(21,UUID.class).toString(),rs.getString(22)); }
    private LogisticsOperationsService.DispatchView view(DispatchRow row, boolean buyer) { LogisticsOperationsService.AssignmentView assignment=row.responsibleMembershipId()==null?null:new LogisticsOperationsService.AssignmentView(buyer?null:row.responsibleMembershipId().toString(),buyer?null:row.responsibleDisplayName(),buyer?null:row.vehicleReference(),buyer?null:row.routeName()); List<String> alerts=new ArrayList<>(); if("OUT_OF_RANGE".equals(row.temperatureStatus()))alerts.add("TEMPERATURE_ALERT"); if("IN_ROUTE".equals(row.status())&&row.podStatus()==null)alerts.add("POD_PENDING"); LogisticsOperationsService.DispatchView value=new LogisticsOperationsService.DispatchView(row.id().toString(),row.dispatchNumber(),row.reservationId().toString(),row.salesOrderId().toString(),row.salesOrderNumber(),row.clientAccountId().toString(),row.status(),row.destination(),row.windowStart(),row.windowEnd(),row.eta(),assignment,row.temperatureMin(),row.temperatureMax(),row.temperatureUnit(),row.temperatureStatus(),row.podId(),row.podStatus(),row.version(),row.updatedAt(),alerts); return buyer?value.buyerSafe():value; }
    private LogisticsOperationsService.DispatchEventView event(ResultSet rs, boolean buyer) throws java.sql.SQLException { String type=rs.getString(2); return new LogisticsOperationsService.DispatchEventView(rs.getObject(1).toString(),buyer?buyerEvent(type):type,buyer?null:rs.getString(3),buyer?null:rs.getString(4),rs.getTimestamp(5).toInstant().toString(),rs.getBoolean(6),buyer?buyerSummary(type,rs.getString(4)):rs.getString(4)); }
    private static String buyerEvent(String type){return switch(type){case "logistics.dispatch.scheduled","logistics.dispatch.reprogrammed"->"DELIVERY_SCHEDULED";case "logistics.dispatch.route-started"->"IN_TRANSIT";case "logistics.dispatch.delivered","logistics.pod.completed"->"DELIVERED";case "logistics.dispatch.cancelled"->"DELIVERY_CANCELLED";case "logistics.dispatch.incident-recorded","logistics.dispatch.buyer-temperature-review"->"DELIVERY_REVIEW";default->"DELIVERY_UPDATED";};}
    private static String buyerSummary(String type,String to){return buyerEvent(type);}

    private LogisticsOperationsService.DispatchView replay(UUID tenant, UUID workspace, String operation, String key, String hash) { lockIdempotency(tenant, workspace, operation, key); Idem value=jdbc.query("select response_json,request_hash from logistics.command_idempotency where tenant_id=? and workspace_id=? and operation=? and idempotency_key=?",rs->rs.next()?new Idem(rs.getString(1),rs.getString(2)):null,tenant,workspace,operation,key); if(value==null)return null; if(!value.hash().equalsIgnoreCase(hash))throw error("IDEMPOTENCY_PAYLOAD_CONFLICT",false); return detail(tenant.toString(),workspace.toString(),null,value.resource()); }
    private void lockIdempotency(UUID tenant, UUID workspace, String operation, String key) { jdbc.query("select pg_advisory_xact_lock(hashtext(?))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null, tenant + "|" + workspace + "|" + operation + "|" + key); }
    private void saveIdempotency(UUID tenant, UUID workspace, String operation, String key, String hash, UUID resource, long now) { if(jdbc.update("insert into logistics.command_idempotency(tenant_id,workspace_id,operation,idempotency_key,request_hash,response_json,created_at) values (?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,operation,idempotency_key) do nothing",tenant,workspace,operation,key,hash,resource.toString(),timestamp(now))!=1) { Idem prior=jdbc.query("select response_json,request_hash from logistics.command_idempotency where tenant_id=? and workspace_id=? and operation=? and idempotency_key=?",rs->rs.next()?new Idem(rs.getString(1),rs.getString(2)):null,tenant,workspace,operation,key); if(prior==null||!prior.hash().equalsIgnoreCase(hash))throw error("IDEMPOTENCY_PAYLOAD_CONFLICT",false); } }
    private long nextDispatchNumber(UUID tenant, UUID workspace, int year) { jdbc.update("insert into logistics.dispatch_number_counter(tenant_id,workspace_id,dispatch_year,next_value) values (?,?,?,1) on conflict (tenant_id,workspace_id,dispatch_year) do nothing",tenant,workspace,year); Long value=jdbc.queryForObject("select next_value from logistics.dispatch_number_counter where tenant_id=? and workspace_id=? and dispatch_year=? for update",Long.class,tenant,workspace,year); jdbc.update("update logistics.dispatch_number_counter set next_value=? where tenant_id=? and workspace_id=? and dispatch_year=?",value+1,tenant,workspace,year); return value; }
    private double averageMinutes(UUID tenant, UUID workspace, String startEvent, String endEvent, Instant from, Instant to) {
        String sql = "select coalesce(avg(extract(epoch from (finish.occurred_at-start.occurred_at))/60.0),0) "
                + "from logistics.dispatch_order d "
                + "join logistics.dispatch_event start on start.tenant_id=d.tenant_id and start.workspace_id=d.workspace_id and start.dispatch_order_id=d.id and start.event_type=? "
                + "join logistics.dispatch_event finish on finish.tenant_id=d.tenant_id and finish.workspace_id=d.workspace_id and finish.dispatch_order_id=d.id and finish.event_type=? "
                + "where d.tenant_id=? and d.workspace_id=? and d.created_at>=? and d.created_at<?";
        return jdbc.query(sql, rs -> rs.next() ? rs.getDouble(1) : 0d, startEvent, endEvent, tenant, workspace,
                timestamp(from.toEpochMilli()), timestamp(to.toEpochMilli()));
    }
    private static void pageCheck(int page,int size){if(page<0||size<1||size>MAX_PAGE_SIZE)throw error("INVALID_REQUEST",false);}
    private static String sort(String value,String... pairs){String[] parts=value==null||value.isBlank()?new String[]{"updatedAt"}:value.split(",",-1);String key=parts[0];if(parts.length>2||(parts.length==2&&!"asc".equalsIgnoreCase(parts[1])&&!"desc".equalsIgnoreCase(parts[1])))throw error("INVALID_INVENTORY_SORT",false);String direction=parts.length==2?" "+parts[1].toLowerCase(Locale.ROOT):"";for(int i=0;i<pairs.length;i+=2)if(pairs[i].equals(key)){if(direction.isBlank())return pairs[i+1];return java.util.Arrays.stream(pairs[i+1].split(",")).map(term->{String normalized=term.trim().replace(" asc","").replace(" desc","");int nulls=normalized.indexOf(" nulls ");return nulls<0?normalized+direction:normalized.substring(0,nulls)+direction+normalized.substring(nulls);}).collect(java.util.stream.Collectors.joining(","));}throw error("INVALID_INVENTORY_SORT",false);}
    private static String enumValue(String value,String field,Enum<?>[] allowed){if(value==null||value.isBlank())throw error("INVALID_REQUEST",false);String normalized=value.trim().toUpperCase(Locale.ROOT);for(Enum<?> candidate:allowed)if(candidate.name().equals(normalized))return normalized;throw error("INVALID_REQUEST",false);}
    private static String hash(String operation,Object...values){String canonical=operation+"|"+java.util.Arrays.stream(values).map(v->v==null?"<null>":String.valueOf(v).trim()).reduce((a,b)->a+"|"+b).orElse("");try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private boolean exists(String sql,Object...args){return jdbc.query(sql,rs->{return rs.next();},args);}
    private static Instant instant(ResultSet rs,int index)throws java.sql.SQLException{Timestamp value=rs.getTimestamp(index);return value==null?null:value.toInstant();}
    private static Timestamp timestamp(long epoch){return Timestamp.from(Instant.ofEpochMilli(epoch));}
    private static UUID uuid(String value){try{return UUID.fromString(value);}catch(RuntimeException e){throw error("INVALID_REQUEST",false);}}
    private static LogisticsException error(String code,boolean notFound){return new LogisticsException(code,notFound);}
    private record DispatchRow(UUID id,String dispatchNumber,UUID reservationId,UUID salesOrderId,UUID clientAccountId,String status,String destination,Instant windowStart,Instant windowEnd,Instant eta,UUID responsibleMembershipId,String responsibleDisplayName,String vehicleReference,String routeName,BigDecimal temperatureMin,BigDecimal temperatureMax,String temperatureUnit,String temperatureStatus,long version,Instant updatedAt,String salesOrderNumber,String podId,String podStatus){}
    private record Idem(String resource,String hash){}
    private record TemperaturePolicy(BigDecimal min,BigDecimal max,String unit,String status){TemperatureReadingStatus status(BigDecimal celsius){if(min==null||max==null)return TemperatureReadingStatus.UNKNOWN;return celsius.compareTo(min)>=0&&celsius.compareTo(max)<=0?TemperatureReadingStatus.WITHIN_RANGE:TemperatureReadingStatus.OUT_OF_RANGE;}}
}
