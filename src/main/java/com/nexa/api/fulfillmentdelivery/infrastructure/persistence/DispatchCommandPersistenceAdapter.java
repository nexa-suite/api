package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchCommandPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.OperationalHandoffNotificationPort;
import com.nexa.api.fulfillmentdelivery.domain.delivery.DeliveryAttempt;
import com.nexa.api.fulfillmentdelivery.domain.delivery.DeliveryAttemptLine;
import com.nexa.api.fulfillmentdelivery.domain.delivery.DeliveryAttemptStatus;
import com.nexa.api.fulfillmentdelivery.domain.dispatchorder.DeliveryWindow;
import com.nexa.api.fulfillmentdelivery.domain.dispatchorder.DispatchOrder;
import com.nexa.api.fulfillmentdelivery.domain.incident.DeliveryIncident;
import com.nexa.api.fulfillmentdelivery.domain.incident.IncidentSeverity;
import com.nexa.api.fulfillmentdelivery.domain.incident.IncidentType;
import com.nexa.api.fulfillmentdelivery.domain.proofofdelivery.ProofOfDeliveryRecord;
import com.nexa.api.fulfillmentdelivery.domain.proofofdelivery.ProofOfDeliveryStatus;
import com.nexa.api.fulfillmentdelivery.domain.temperaturereading.TemperatureReading;
import com.nexa.api.fulfillmentdelivery.domain.temperaturereading.TemperatureReadingStatus;
import com.nexa.api.fulfillmentdelivery.domain.temperaturereading.TemperatureScale;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.inventoryavailability.application.port.WarehouseLogisticsFulfillmentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Owns the persisted DispatchOrder command workflows. */
@Repository
@Profile("!test")
public class DispatchCommandPersistenceAdapter extends DispatchJdbcSupport implements DispatchCommandPersistencePort {
    public DispatchCommandPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                             WarehouseLogisticsFulfillmentPort warehouseFulfillment,
                                             OperationalHandoffNotificationPort handoffNotifications) {
        super(jdbc, changeFeed, warehouseFulfillment, handoffNotifications);
    }

    @Override
    public LogisticsOperationsService.DispatchView create(String tenantId, String workspaceId, String reservationId,
                                                           long reservationVersion, String actorMembershipId,
                                                           String key, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID reservation = uuid(reservationId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("dispatch-create", reservationId, reservationVersion);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-create", key, requestHash);
        if (replay != null) return replay;

        // Canonical fulfillment and legacy dispatch both mutate stock and can
        // create a delivery. Serialize on the Sales Order before touching the
        // reservation so the two paths cannot win the same order concurrently.
        UUID salesOrderId = jdbc.query("select sales_order_id from warehouse.inventory_reservation where tenant_id=? and workspace_id=? and id=?",
                        (rs, row) -> rs.getObject(1, UUID.class), tenant, workspace, reservation)
                .stream().findFirst().orElseThrow(() -> error("RESOURCE_NOT_FOUND", true));
        CanonicalOrderRow order = jdbc.query("select id,commercial_commitment_id from sales.sales_order where tenant_id=? and workspace_id=? and id=? for update",
                        (rs, row) -> new CanonicalOrderRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                        tenant, workspace, salesOrderId).stream().findFirst()
                .orElseThrow(() -> error("RESOURCE_NOT_FOUND", true));
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.fulfillment where tenant_id=? and workspace_id=? and sales_order_id=?)",
                Boolean.class, tenant, workspace, order.id()))) {
            throw error("CANONICAL_FULFILLMENT_ALREADY_EXISTS", false);
        }

        WarehouseLogisticsFulfillmentPort.DispatchReservationSnapshot source = warehouseFulfillment
                .loadReservedReservation(tenantId, workspaceId, reservationId, reservationVersion, Instant.ofEpochMilli(now));
        UUID existing = jdbc.query("select id from logistics.dispatch_order " +
                        "where tenant_id=? and workspace_id=? and inventory_reservation_id=? for update",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenant, workspace, reservation);
        if (existing != null) return detailView(tenantId, workspaceId, null, existing.toString());

        UUID id = UUID.randomUUID();
        int year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).getYear();
        long sequence = nextDispatchNumber(tenant, workspace, year);
        String number = String.format("DO-%04d-%06d", year, sequence);
        BusinessCard card = businessCard(tenant, workspace, source.salesOrderId(), source.destinationSnapshot());
        jdbc.update("insert into logistics.dispatch_order(id,tenant_id,workspace_id,dispatch_number," +
                        "inventory_reservation_id,sales_order_id,client_account_id,status,destination_snapshot," +
                        "client_code_snapshot,client_name_snapshot,delivery_area_snapshot,priority," +
                        "temperature_min,temperature_max,temperature_unit,temperature_status,created_at,updated_at,version) " +
                        "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                id, tenant, workspace, number, reservation, source.salesOrderId(), source.clientAccountId(),
                "READY_FOR_OPERATIONS", source.destinationSnapshot(), card.clientCode(), card.clientName(),
                card.deliveryArea(), card.priority(), source.temperatureMin(), source.temperatureMax(),
                source.temperatureUnit(), source.temperatureStatus(), timestamp(now), timestamp(now));
        jdbc.update("insert into logistics.delivery(id,tenant_id,workspace_id,dispatch_order_id,status,destination_snapshot,created_at,updated_at,version) " +
                        "values (?,?,?,?,?,?,?,?,0)",
                id, tenant, workspace, id, "PLANNED", source.destinationSnapshot(), timestamp(now), timestamp(now));
        appendEvent(tenant, workspace, id, "logistics.dispatch.created", null, "READY_FOR_OPERATIONS", actor,
                false, null, now, source.clientAccountId());
        saveIdempotency(tenant, workspace, "dispatch-create", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, id.toString());
    }

    @Override
    public LogisticsOperationsService.DispatchView prepare(String tenantId, String workspaceId, String dispatchId,
                                                            long version, String actorMembershipId, String key, long now) {
        return statusCommand(tenantId, workspaceId, dispatchId, version, actorMembershipId, key,
                "dispatch-preparation", "logistics.dispatch.preparation-started", now, "PREPARE", null);
    }

    @Override
    public LogisticsOperationsService.DispatchView assign(String tenantId, String workspaceId, String dispatchId,
                                                           long version, String actorMembershipId, String key,
                                                           String responsibleMembershipId, String vehicleReference,
                                                           String routeName, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        UUID membership = uuid(responsibleMembershipId);
        String requestHash = hash("dispatch-assignment", dispatchId, version, responsibleMembershipId,
                vehicleReference, routeName);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-assignment", key, requestHash);
        if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        String display = jdbc.query("select u.display_name from tenant_management.workspace_membership m " +
                        "join tenant_management.workspace w on w.id=m.workspace_id " +
                        "join iam.user_account u on u.id=m.user_id " +
                        "join tenant_management.membership_role_definition a on a.membership_id=m.id " +
                        "join tenant_management.role_definition r on r.id=a.role_id " +
                        "where m.id=? and w.tenant_id=? and w.id=? and r.code='logistics' " +
                        "and r.status='ACTIVE' and m.status='ACTIVE'",
                rs -> rs.next() ? rs.getString(1) : null, membership, tenant, workspace);
        if (display == null) throw error("RESPONSIBLE_MEMBERSHIP_INVALID", false);
        DispatchOrder aggregate = aggregate(row);
        aggregate.assign(new com.nexa.api.fulfillmentdelivery.domain.dispatchorder.TransportAssignment(
                membership, display, vehicleReference, routeName));
        updateAssignment(tenant, workspace, row, aggregate, membership, display, vehicleReference, routeName,
                now, actor, "logistics.dispatch.assigned", false, null);
        saveIdempotency(tenant, workspace, "dispatch-assignment", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    @Override
    public LogisticsOperationsService.DispatchView schedule(String tenantId, String workspaceId, String dispatchId,
                                                             long version, String actorMembershipId, String key,
                                                             Instant startsAt, Instant endsAt, Instant eta, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("dispatch-schedule", dispatchId, version, startsAt, endsAt, eta);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-schedule", key, requestHash);
        if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        DispatchOrder aggregate = aggregate(row);
        aggregate.schedule(new DeliveryWindow(startsAt, endsAt), eta);
        updateSchedule(tenant, workspace, row, aggregate, startsAt, endsAt, eta, now, actor,
                "logistics.dispatch.scheduled", true, null);
        saveIdempotency(tenant, workspace, "dispatch-schedule", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    @Override
    public LogisticsOperationsService.DispatchView ready(String tenantId, String workspaceId, String dispatchId,
                                                          long version, String actorMembershipId, String key, long now) {
        return statusCommand(tenantId, workspaceId, dispatchId, version, actorMembershipId, key,
                "dispatch-ready", "logistics.dispatch.ready", now, "READY", null);
    }

    @Override
    public LogisticsOperationsService.DispatchView temperature(String tenantId, String workspaceId, String dispatchId,
                                                                long version, String actorMembershipId, String key,
                                                                BigDecimal value, String unit, Instant recordedAt,
                                                                String source, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("dispatch-temperature", dispatchId, version, value, unit, recordedAt, source);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-temperature", key, requestHash);
        if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        if (!(row.status().equals("READY_FOR_ROUTE") || row.status().equals("IN_ROUTE") || row.status().equals("INCIDENT"))) {
            throw error("INVALID_TRANSITION", false);
        }
        TemperatureScale scale = TemperatureScale.from(unit);
        TemperatureReadingStatus readingStatus = new TemperaturePolicy(row.temperatureMin(), row.temperatureMax())
                .status(scale.toCelsius(value));
        TemperatureReading reading = new TemperatureReading(value, scale,
                recordedAt == null ? Instant.now() : recordedAt,
                source == null || source.isBlank() ? "MANUAL" : source, readingStatus);
        jdbc.update("insert into logistics.temperature_reading(id,tenant_id,workspace_id,dispatch_order_id,value,unit," +
                        "recorded_at,source,status,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, workspace, id, reading.value(), reading.scale().name(),
                timestamp(reading.recordedAt()), reading.source(), reading.status().name(), timestamp(now));
        boolean excursion = readingStatus == TemperatureReadingStatus.OUT_OF_RANGE && row.status().equals("IN_ROUTE");
        if (excursion) {
            if (jdbc.update("update logistics.dispatch_order set status='INCIDENT',temperature_status=?,updated_at=?,version=version+1 " +
                            "where tenant_id=? and workspace_id=? and id=? and version=?", readingStatus.name(), timestamp(now),
                    tenant, workspace, id, row.version()) != 1) throw error("CONCURRENCY_CONFLICT", false);
            jdbc.update("insert into logistics.delivery_incident(id,tenant_id,workspace_id,dispatch_order_id," +
                            "incident_type,severity,buyer_visible,description,occurred_at,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), tenant, workspace, id, "TEMPERATURE_EXCURSION", "HIGH", false,
                    "Temperature reading is outside the configured dispatch range", timestamp(reading.recordedAt()), timestamp(now));
            appendEvent(tenant, workspace, id, "logistics.dispatch.buyer-temperature-review", row.status(), "INCIDENT",
                    actor, true, "Delivery review required", now, row.clientAccountId());
        } else {
            if (jdbc.update("update logistics.dispatch_order set temperature_status=?,updated_at=?,version=version+1 " +
                            "where tenant_id=? and workspace_id=? and id=? and version=?", readingStatus.name(), timestamp(now),
                    tenant, workspace, id, row.version()) != 1) throw error("CONCURRENCY_CONFLICT", false);
        }
        appendEvent(tenant, workspace, id, "logistics.dispatch.temperature-recorded", row.status(),
                excursion ? "INCIDENT" : row.status(), actor, false, null, now, row.clientAccountId());
        saveIdempotency(tenant, workspace, "dispatch-temperature", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    @Override
    public LogisticsOperationsService.DispatchView incident(String tenantId, String workspaceId, String dispatchId,
                                                             long version, String actorMembershipId, String key,
                                                             String type, String severity, boolean buyerVisible,
                                                             String description, Instant occurredAt, String resolution,
                                                             long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("dispatch-incident", dispatchId, version, type, severity, buyerVisible,
                description, occurredAt, resolution);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-incident", key, requestHash);
        if (replay != null) return replay;
        IncidentType incidentType = IncidentType.valueOf(enumValue(type, "incidentType", IncidentType.values()));
        IncidentSeverity incidentSeverity = IncidentSeverity.valueOf(enumValue(severity, "severity", IncidentSeverity.values()));
        DeliveryIncident incident = new DeliveryIncident(incidentType, incidentSeverity, buyerVisible, description,
                occurredAt == null ? Instant.now() : occurredAt, resolution);
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        DispatchOrder aggregate = aggregate(row);
        if (!row.status().equals("INCIDENT")) {
            aggregate.recordIncident();
            updateStatus(tenant, workspace, row, aggregate, now, actor,
                    "logistics.dispatch.incident-recorded", buyerVisible, description);
        } else {
            touch(tenant, workspace, row, now);
            appendEvent(tenant, workspace, id, "logistics.dispatch.incident-recorded", row.status(), row.status(),
                    actor, buyerVisible, description, now, row.clientAccountId());
        }
        jdbc.update("insert into logistics.delivery_incident(id,tenant_id,workspace_id,dispatch_order_id,incident_type," +
                        "severity,buyer_visible,description,occurred_at,resolution,created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, workspace, id, incident.type().name(), incident.severity().name(),
                incident.buyerVisible(), incident.description(), timestamp(incident.occurredAt()), incident.resolution(), timestamp(now));
        saveIdempotency(tenant, workspace, "dispatch-incident", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    @Override
    public LogisticsOperationsService.DispatchView reprogram(String tenantId, String workspaceId, String dispatchId,
                                                              long version, String actorMembershipId, String key,
                                                              Instant startsAt, Instant endsAt, Instant eta, String reason,
                                                              long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("dispatch-reprogram", dispatchId, version, startsAt, endsAt, eta, reason);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-reprogram", key, requestHash);
        if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        DispatchOrder aggregate = aggregate(row);
        aggregate.reprogram(new DeliveryWindow(startsAt, endsAt), eta);
        updateSchedule(tenant, workspace, row, aggregate, startsAt, endsAt, eta, now, actor,
                "logistics.dispatch.reprogrammed", true, reason);
        saveIdempotency(tenant, workspace, "dispatch-reprogram", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    @Override
    public LogisticsOperationsService.DispatchView cancel(String tenantId, String workspaceId, String dispatchId,
                                                           long version, String actorMembershipId, String key,
                                                           String reason, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("dispatch-cancel", dispatchId, version, reason);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-cancel", key, requestHash);
        if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        DispatchOrder aggregate = aggregate(row);
        aggregate.cancel();
        warehouseFulfillment.releaseReservation(tenantId, workspaceId, row.reservationId().toString(), actorMembershipId,
                id.toString(), reason, Instant.ofEpochMilli(now));
        updateStatus(tenant, workspace, row, aggregate, now, actor, "logistics.dispatch.cancelled", true, reason);
        if (jdbc.update("update logistics.delivery set status='CANCELLED',updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and status not in ('DELIVERED','CANCELLED')",
                timestamp(now), tenant, workspace, id) != 1) throw error("CONCURRENCY_CONFLICT", false);
        saveIdempotency(tenant, workspace, "dispatch-cancel", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    @Override
    public LogisticsOperationsService.DispatchView failedAttempt(String tenantId, String workspaceId, String dispatchId,
                                                                  long version, String actorMembershipId, String key,
                                                                  String failureReason, Instant occurredAt, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("delivery-attempt-failed", dispatchId, version, failureReason, occurredAt);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "delivery-attempt-failed", key, requestHash);
        if (replay != null) return replay;
        Instant effectiveOccurredAt = occurredAt == null ? Instant.now() : occurredAt;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        DispatchOrder aggregate = aggregate(row);
        aggregate.recordFailedAttempt();
        DeliveryAttempt attempt = new DeliveryAttempt(UUID.randomUUID(), id, nextAttemptNumber(tenant, workspace, id),
                DeliveryAttemptStatus.FAILED, failureReason, effectiveOccurredAt, List.of());
        insertAttempt(tenant, workspace, attempt, null, now);
        touch(tenant, workspace, row, now);
        appendEvent(tenant, workspace, id, "logistics.delivery.attempt-failed", row.status(), row.status(), actor,
                true, "Delivery attempt failed", now, row.clientAccountId());
        CanonicalOutbox.append(jdbc, "DELIVERY_FAILED", "DispatchOrder", id, tenant, workspace,
                Instant.ofEpochMilli(now), "delivery-attempt-" + attempt.id(), null, "1.0", attempt.id().toString(), Map.of(
                        "deliveryId", id, "attemptId", attempt.id(), "status", "FAILED"));
        saveIdempotency(tenant, workspace, "delivery-attempt-failed", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    @Override
    public LogisticsOperationsService.DispatchView partial(String tenantId, String workspaceId, String dispatchId,
                                                             long version, String actorMembershipId, String key,
                                                             List<LogisticsOperationsService.DeliveryLineCommand> deliveredLines,
                                                             Instant completedAt, String notes, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        List<LogisticsOperationsService.DeliveryLineCommand> normalized = normalizeLines(deliveredLines);
        String requestHash = hash("delivery-partial", dispatchId, version, canonicalLines(normalized), completedAt, notes);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "delivery-partial", key, requestHash);
        if (replay != null) return replay;
        Instant effectiveCompletedAt = completedAt == null ? Instant.now() : completedAt;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        List<ObligationLine> obligations = obligations(tenant, workspace, row.salesOrderId());
        Map<String, BigDecimal> alreadyDelivered = deliveredQuantities(tenant, workspace, id);
        List<LogisticsOperationsService.DeliveryLineCommand> remaining = remainingAfter(obligations, alreadyDelivered, normalized);
        if (remaining.isEmpty()) throw error("INVALID_REQUEST", false);
        DispatchOrder aggregate = aggregate(row);
        aggregate.deliverPartially();
        DeliveryAttempt attempt = new DeliveryAttempt(UUID.randomUUID(), id, nextAttemptNumber(tenant, workspace, id),
                DeliveryAttemptStatus.PARTIAL, null, effectiveCompletedAt, normalized.stream()
                .map(line -> new DeliveryAttemptLine(line.catalogItemId(), line.quantity(), line.unit())).toList());
        insertAttempt(tenant, workspace, attempt, notes, now);
        UUID continuationId = UUID.randomUUID();
        jdbc.update("insert into logistics.continuation_delivery(id,tenant_id,workspace_id,source_delivery_id,sales_order_id,client_account_id,status,created_at,updated_at,version) values (?,?,?,?,?,?,'OPEN'::varchar,?,?,0)",
                continuationId, tenant, workspace, id, row.salesOrderId(), row.clientAccountId(), timestamp(now), timestamp(now));
        for (LogisticsOperationsService.DeliveryLineCommand line : remaining) {
            jdbc.update("insert into logistics.continuation_delivery_line(id,tenant_id,workspace_id,continuation_delivery_id,catalog_item_id,quantity,unit,created_at) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), tenant, workspace, continuationId, line.catalogItemId(), line.quantity(), line.unit(), timestamp(now));
        }
        updateStatus(tenant, workspace, row, aggregate, now, actor, "logistics.delivery.partially-completed", true,
                "Delivery partially completed");
        updateDeliveryStatus(tenant, workspace, id, "IN_TRANSIT", "PARTIAL", null, now);
        appendEvent(tenant, workspace, id, "logistics.delivery.continuation-created", row.status(), "PARTIAL", actor,
                true, "Continuation delivery required", now, row.clientAccountId());
        CanonicalOutbox.append(jdbc, "DELIVERY_PARTIALLY_COMPLETED", "DispatchOrder", id, tenant, workspace,
                Instant.ofEpochMilli(now), "delivery-partial-" + attempt.id(), null, "1.0", attempt.id().toString(), Map.of(
                        "deliveryId", id, "attemptId", attempt.id(), "status", "PARTIAL", "continuationDeliveryId", continuationId));
        CanonicalOutbox.append(jdbc, "CONTINUATION_REQUIRED", "ContinuationDelivery", continuationId, tenant, workspace,
                Instant.ofEpochMilli(now), "delivery-partial-" + attempt.id(), null, "1.0", continuationId.toString(), Map.of(
                        "continuationDeliveryId", continuationId, "sourceDeliveryId", id, "salesOrderId", row.salesOrderId(),
                        "status", "OPEN"));
        saveIdempotency(tenant, workspace, "delivery-partial", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    @Override
    public LogisticsOperationsService.DispatchView complete(String tenantId, String workspaceId, String dispatchId,
                                                             long version, String actorMembershipId, String key,
                                                             String receiverName, Instant completedAt, String notes,
                                                             boolean photoDeclared, boolean signatureDeclared, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("dispatch-delivery", dispatchId, version, receiverName, completedAt, notes,
                photoDeclared, signatureDeclared);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, "dispatch-delivery", key, requestHash);
        if (replay != null) return replay;
        Instant effectiveCompletedAt = completedAt == null ? Instant.now() : completedAt;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        List<ObligationLine> obligations = obligations(tenant, workspace, row.salesOrderId());
        List<LogisticsOperationsService.DeliveryLineCommand> finalLines = remainingAfter(obligations,
                deliveredQuantities(tenant, workspace, id), List.of());
        if (finalLines.isEmpty()) throw error("INVALID_REQUEST", false);
        DispatchOrder aggregate = aggregate(row);
        aggregate.deliver();
        ProofOfDeliveryRecord pod = new ProofOfDeliveryRecord(receiverName,
                effectiveCompletedAt, notes, photoDeclared, signatureDeclared,
                ProofOfDeliveryStatus.COMPLETED);
        UUID podId = UUID.randomUUID();
        DeliveryAttempt attempt = new DeliveryAttempt(UUID.randomUUID(), id, nextAttemptNumber(tenant, workspace, id),
                DeliveryAttemptStatus.FINAL, null, effectiveCompletedAt, finalLines.stream()
                .map(line -> new DeliveryAttemptLine(line.catalogItemId(), line.quantity(), line.unit())).toList());
        updateStatus(tenant, workspace, row, aggregate, now, actor, "logistics.dispatch.delivered", true, null);
        updateDeliveryStatus(tenant, workspace, id, "IN_TRANSIT", "DELIVERED", effectiveCompletedAt, now);
        insertAttempt(tenant, workspace, attempt, notes, now);
        jdbc.update("insert into logistics.proof_of_delivery(id,tenant_id,workspace_id,dispatch_order_id,receiver_name," +
                        "completed_at,notes,photo_evidence_declared,signature_evidence_declared,status,created_at) " +
                        "values (?,?,?,?,?,?,?,?,?,?,?)", podId, tenant, workspace, id, pod.receiverName(),
                timestamp(pod.completedAt()), pod.notes(), pod.photoEvidenceDeclared(), pod.signatureEvidenceDeclared(),
                pod.status().name(), timestamp(now));
        appendEvent(tenant, workspace, id, "logistics.pod.completed", row.status(), "DELIVERED", actor, true, null,
                now, row.clientAccountId());
        CanonicalOutbox.append(jdbc, "DISPATCH_DELIVERED", "DispatchOrder", id, tenant, workspace,
                Instant.ofEpochMilli(now), "dispatch-" + id, null, "1.0", Map.of(
                        "dispatchOrderId", id, "salesOrderId", row.salesOrderId(), "podId", podId, "podStatus", "COMPLETED"));
        CanonicalOutbox.append(jdbc, "DELIVERY_COMPLETED", "DispatchOrder", id, tenant, workspace,
                Instant.ofEpochMilli(now), "dispatch-" + id, null, "1.0", Map.of(
                        "dispatchOrderId", id, "salesOrderId", row.salesOrderId(), "podId", podId, "status", "DELIVERED"));
        CanonicalOutbox.append(jdbc, "POD_COMPLETED", "ProofOfDelivery", podId, tenant, workspace,
                Instant.ofEpochMilli(now), "dispatch-" + id, null, "1.0", Map.of(
                        "dispatchOrderId", id, "salesOrderId", row.salesOrderId(), "podId", podId, "status", "COMPLETED"));
        saveIdempotency(tenant, workspace, "dispatch-delivery", key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    private void insertAttempt(UUID tenant, UUID workspace, DeliveryAttempt attempt, String notes, long now) {
        jdbc.update("insert into logistics.delivery_attempt(id,tenant_id,workspace_id,delivery_id,attempt_number,status,failure_reason,notes,occurred_at,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                attempt.id(), tenant, workspace, attempt.deliveryId(), attempt.number(), attempt.status().name(),
                attempt.failureReason(), notes == null ? null : notes.trim(), timestamp(attempt.occurredAt()), timestamp(now));
        for (DeliveryAttemptLine line : attempt.lines()) {
            jdbc.update("insert into logistics.delivery_attempt_line(id,tenant_id,workspace_id,delivery_attempt_id,catalog_item_id,quantity,unit,created_at) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), tenant, workspace, attempt.id(), line.catalogItemId(), line.quantity(), line.unit(), timestamp(now));
        }
    }

    private int nextAttemptNumber(UUID tenant, UUID workspace, UUID deliveryId) {
        Integer value = jdbc.queryForObject("select coalesce(max(attempt_number),0)+1 from logistics.delivery_attempt where tenant_id=? and workspace_id=? and delivery_id=?",
                Integer.class, tenant, workspace, deliveryId);
        return value == null ? 1 : value;
    }

    private List<LogisticsOperationsService.DeliveryLineCommand> normalizeLines(List<LogisticsOperationsService.DeliveryLineCommand> values) {
        if (values == null || values.isEmpty() || values.size() > 100) throw error("INVALID_REQUEST", false);
        Map<String, LogisticsOperationsService.DeliveryLineCommand> normalized = new LinkedHashMap<>();
        for (LogisticsOperationsService.DeliveryLineCommand value : values) {
            if (value == null || value.catalogItemId() == null || value.catalogItemId().isBlank()
                    || value.quantity() == null || value.quantity().signum() <= 0 || value.unit() == null || value.unit().isBlank()
                    || value.catalogItemId().trim().length() > 64 || value.unit().trim().length() > 32
                    || value.quantity().stripTrailingZeros().scale() > 4) throw error("INVALID_REQUEST", false);
            String catalogItemId = value.catalogItemId().trim();
            if (normalized.put(catalogItemId, new LogisticsOperationsService.DeliveryLineCommand(catalogItemId,
                    value.quantity(), value.unit().trim().toUpperCase(Locale.ROOT))) != null) {
                throw error("INVALID_REQUEST", false);
            }
        }
        return List.copyOf(normalized.values());
    }

    private String canonicalLines(List<LogisticsOperationsService.DeliveryLineCommand> values) {
        return values.stream().map(value -> value.catalogItemId() + ":" + value.quantity().stripTrailingZeros().toPlainString() + ":" + value.unit()).toList().toString();
    }

    private List<ObligationLine> obligations(UUID tenant, UUID workspace, UUID salesOrderId) {
        return jdbc.query("select l.catalog_item_id,l.quantity,l.unit from sales.sales_order_line l join sales.sales_order o on o.id=l.sales_order_id and o.tenant_id=? and o.workspace_id=? where l.sales_order_id=? order by l.catalog_item_id",
                (rs, row) -> new ObligationLine(rs.getString("catalog_item_id"), rs.getBigDecimal("quantity"), rs.getString("unit").toUpperCase(Locale.ROOT)),
                tenant, workspace, salesOrderId);
    }

    private Map<String, BigDecimal> deliveredQuantities(UUID tenant, UUID workspace, UUID deliveryId) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        jdbc.query("select l.catalog_item_id,sum(l.quantity) quantity from logistics.delivery_attempt a join logistics.delivery_attempt_line l on l.tenant_id=a.tenant_id and l.workspace_id=a.workspace_id and l.delivery_attempt_id=a.id where a.tenant_id=? and a.workspace_id=? and a.delivery_id=? group by l.catalog_item_id",
                (ResultSetExtractor<Void>) rs -> { while (rs.next()) values.put(rs.getString("catalog_item_id"), rs.getBigDecimal("quantity")); return null; },
                tenant, workspace, deliveryId);
        return values;
    }

    private List<LogisticsOperationsService.DeliveryLineCommand> remainingAfter(List<ObligationLine> obligations,
                                                                                  Map<String, BigDecimal> alreadyDelivered,
                                                                                  List<LogisticsOperationsService.DeliveryLineCommand> additional) {
        Map<String, BigDecimal> remaining = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        for (ObligationLine obligation : obligations) {
            BigDecimal prior = alreadyDelivered.getOrDefault(obligation.catalogItemId(), BigDecimal.ZERO);
            if (prior.compareTo(obligation.quantity()) > 0) throw error("INVALID_REQUEST", false);
            remaining.put(obligation.catalogItemId(), obligation.quantity().subtract(prior));
            units.put(obligation.catalogItemId(), obligation.unit());
        }
        for (LogisticsOperationsService.DeliveryLineCommand line : additional) {
            if (!remaining.containsKey(line.catalogItemId()) || !units.get(line.catalogItemId()).equalsIgnoreCase(line.unit())
                    || line.quantity().compareTo(remaining.get(line.catalogItemId())) > 0) throw error("INVALID_REQUEST", false);
            remaining.put(line.catalogItemId(), remaining.get(line.catalogItemId()).subtract(line.quantity()));
        }
        List<LogisticsOperationsService.DeliveryLineCommand> result = new ArrayList<>();
        for (ObligationLine obligation : obligations) {
            BigDecimal value = remaining.get(obligation.catalogItemId());
            if (value.signum() > 0) result.add(new LogisticsOperationsService.DeliveryLineCommand(obligation.catalogItemId(), value, obligation.unit()));
        }
        return result;
    }

    private LogisticsOperationsService.DispatchView statusCommand(String tenantId, String workspaceId,
                                                                   String dispatchId, long version,
                                                                   String actorMembershipId, String key,
                                                                   String operation, String eventType, long now,
                                                                   String command, String reason) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID id = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash(operation, dispatchId, version, reason);
        LogisticsOperationsService.DispatchView replay = replay(tenant, workspace, operation, key, requestHash);
        if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, id, null);
        requireVersion(row, version);
        DispatchOrder aggregate = aggregate(row);
        switch (command) {
            case "PREPARE" -> aggregate.startPreparation();
            case "READY" -> {
                warehouseFulfillment.ensureReservationReady(tenantId, workspaceId, row.reservationId().toString(), Instant.ofEpochMilli(now));
                aggregate.markReadyForRoute();
            }
            default -> throw error("INVALID_REQUEST", false);
        }
        updateStatus(tenant, workspace, row, aggregate, now, actor, eventType, false, reason);
        saveIdempotency(tenant, workspace, operation, key, requestHash, id, now);
        return detailView(tenantId, workspaceId, null, dispatchId);
    }

    private void updateStatus(UUID tenant, UUID workspace, DispatchRow row, DispatchOrder aggregate, long now,
                              UUID actor, String eventType, boolean buyerVisible, String reason) {
        int changed = jdbc.update("update logistics.dispatch_order set status=?,updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and version=?", aggregate.status().name(),
                timestamp(now), tenant, workspace, row.id(), row.version());
        if (changed != 1) throw error("CONCURRENCY_CONFLICT", false);
        appendEvent(tenant, workspace, row.id(), eventType, row.status(), aggregate.status().name(), actor,
                buyerVisible, reason, now, row.clientAccountId());
    }

    private void updateAssignment(UUID tenant, UUID workspace, DispatchRow row, DispatchOrder aggregate,
                                  UUID membership, String display, String vehicle, String route, long now,
                                  UUID actor, String eventType, boolean buyerVisible, String reason) {
        int changed = jdbc.update("update logistics.dispatch_order set status=?,responsible_membership_id=?," +
                        "responsible_display_name_snapshot=?,vehicle_reference=?,route_name=?,updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and version=?", aggregate.status().name(),
                membership, display, vehicle, route, timestamp(now), tenant, workspace, row.id(), row.version());
        if (changed != 1) throw error("CONCURRENCY_CONFLICT", false);
        if (jdbc.update("update logistics.delivery set status='ASSIGNED',updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and status in ('PLANNED','ASSIGNED')",
                timestamp(now), tenant, workspace, row.id()) != 1) throw error("CONCURRENCY_CONFLICT", false);
        appendEvent(tenant, workspace, row.id(), eventType, row.status(), aggregate.status().name(), actor,
                buyerVisible, reason, now, row.clientAccountId());
    }

    private void updateSchedule(UUID tenant, UUID workspace, DispatchRow row, DispatchOrder aggregate,
                                Instant start, Instant end, Instant eta, long now, UUID actor, String eventType,
                                boolean buyerVisible, String reason) {
        int changed = jdbc.update("update logistics.dispatch_order set status=?,delivery_window_start=?," +
                        "delivery_window_end=?,eta=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? " +
                        "and id=? and version=?", aggregate.status().name(), timestamp(start), timestamp(end),
                eta == null ? null : timestamp(eta), timestamp(now), tenant, workspace, row.id(), row.version());
        if (changed != 1) throw error("CONCURRENCY_CONFLICT", false);
        if (jdbc.update("update logistics.delivery set status='DISPATCHED',scheduled_at=?,updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and status in ('PLANNED','ASSIGNED','DISPATCHED','IN_TRANSIT')",
                timestamp(start), timestamp(now), tenant, workspace, row.id()) != 1) throw error("CONCURRENCY_CONFLICT", false);
        appendEvent(tenant, workspace, row.id(), eventType, row.status(), aggregate.status().name(), actor,
                buyerVisible, reason, now, row.clientAccountId());
    }

    private void touch(UUID tenant, UUID workspace, DispatchRow row, long now) {
        if (jdbc.update("update logistics.dispatch_order set updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and version=?", timestamp(now), tenant,
                workspace, row.id(), row.version()) != 1) throw error("CONCURRENCY_CONFLICT", false);
    }

    private void updateDeliveryStatus(UUID tenant, UUID workspace, UUID deliveryId, String expectedStatus,
                                      String nextStatus, Instant deliveredAt, long now) {
        String deliveredAtSql = deliveredAt == null ? "null" : "?";
        String sql = "update logistics.delivery set status=?,delivered_at=" + deliveredAtSql + ",updated_at=?,version=version+1 " +
                "where tenant_id=? and workspace_id=? and id=? and status=?";
        int changed = deliveredAt == null
                ? jdbc.update(sql, nextStatus, timestamp(now), tenant, workspace, deliveryId, expectedStatus)
                : jdbc.update(sql, nextStatus, timestamp(deliveredAt), timestamp(now), tenant, workspace, deliveryId, expectedStatus);
        if (changed != 1) throw error("CONCURRENCY_CONFLICT", false);
    }

    private static void requireVersion(DispatchRow row, long version) {
        if (row == null) throw error("RESOURCE_NOT_FOUND", true);
        if (row.version() != version) throw error("CONCURRENCY_CONFLICT", false);
    }

    private record TemperaturePolicy(BigDecimal min, BigDecimal max) {
        TemperatureReadingStatus status(BigDecimal celsius) {
            if (min == null || max == null) return TemperatureReadingStatus.UNKNOWN;
            return celsius.compareTo(min) >= 0 && celsius.compareTo(max) <= 0
                    ? TemperatureReadingStatus.WITHIN_RANGE : TemperatureReadingStatus.OUT_OF_RANGE;
        }
    }

    private record CanonicalOrderRow(UUID id, UUID commercialCommitmentId) { }

    private record ObligationLine(String catalogItemId, BigDecimal quantity, String unit) { }
}
