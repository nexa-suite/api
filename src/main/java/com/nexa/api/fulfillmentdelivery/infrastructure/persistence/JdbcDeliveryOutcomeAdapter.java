package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.AttemptView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.DeliveryOutcomeResult;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.DeliveryView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.PodView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.RemainingLine;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.TemperatureView;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort.AttemptLine;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort.AttemptRequest;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort.PodRequest;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort.PodSealRequest;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort.TemperatureRequest;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort.TransitionRequest;
import com.nexa.api.fulfillmentdelivery.domain.model.delivery.DeliveryAttemptOutcome;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** SQL adapter for delivery attempts, quantity outcomes, POD and cold-chain evidence. */
@Repository
@Profile("!test")
public class JdbcDeliveryOutcomeAdapter implements DeliveryPersistencePort {
    private final JdbcTemplate jdbc;
    private final ColdChainPolicyQuery coldChain;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcDeliveryOutcomeAdapter(JdbcTemplate jdbc, ColdChainPolicyQuery coldChain, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.coldChain = coldChain;
        this.mapper = mapper;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public DeliveryView find(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        return load(tenantId, workspaceId, deliveryId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DeliveryView transition(TransitionRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(), request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(), request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        DeliveryRow current = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        requireDeliveryTransition(current.status(), request.targetStatus());
        Instant now = Objects.requireNonNull(request.now(), "now");
        if (jdbc.update("update logistics.delivery set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                request.targetStatus(), Timestamp.from(now), request.tenantId(), request.workspaceId(), request.deliveryId(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        insertDeliveryEvent(request.tenantId(), request.workspaceId(), request.deliveryId(), request.operation(),
                request.actorMembershipId(), bounded(request.reason()), now);
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(),
                request.idempotencyKey(), request.requestHash(), request.deliveryId(), now);
        return load(request.tenantId(), request.workspaceId(), request.deliveryId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DeliveryOutcomeResult recordAttempt(AttemptRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        if (request.outcome() == null || request.outcome() == DeliveryAttemptOutcome.PENDING) throw error("DELIVERY_OUTCOME_REQUIRED");
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "ATTEMPT", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "ATTEMPT", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return replayResult(request.tenantId(), request.workspaceId(), request.deliveryId(), prior.resourceId());
        }

        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery.fulfillmentId() == null || delivery.salesOrderId() == null) throw error("DELIVERY_NOT_FULFILLMENT_BACKED");
        if (!SetOfAttemptableDeliveryStatuses.contains(delivery.status())) throw error("DELIVERY_NOT_ATTEMPTABLE");
        FulfillmentRow fulfillment = lockFulfillment(request.tenantId(), request.workspaceId(), delivery.fulfillmentId(), request.clientAccountId());
        if (!fulfillment.id().equals(delivery.fulfillmentId())) throw error("DELIVERY_FULFILLMENT_MISMATCH");
        List<FulfillmentLineRow> currentLines = lockFulfillmentLines(request.tenantId(), request.workspaceId(), fulfillment.id());
        Map<UUID, FulfillmentLineRow> byId = new HashMap<>();
        currentLines.forEach(line -> byId.put(line.id(), line));
        List<AttemptLine> lines = request.lines() == null ? List.of() : request.lines();
        if (request.outcome() == DeliveryAttemptOutcome.FAILED && !lines.isEmpty()) throw error("FAILED_ATTEMPT_CANNOT_RESOLVE_QUANTITY");
        if (request.outcome() != DeliveryAttemptOutcome.FAILED && lines.isEmpty()) throw error("DELIVERY_OUTCOME_LINES_REQUIRED");
        Map<UUID, AttemptLine> uniqueLines = new HashMap<>();
        for (AttemptLine line : lines) {
            validateAttemptLine(line, request.outcome());
            if (byId.get(line.fulfillmentLineId()) == null || uniqueLines.put(line.fulfillmentLineId(), line) != null) throw error("DELIVERY_OUTCOME_LINE_INVALID");
            FulfillmentLineRow current = byId.get(line.fulfillmentLineId());
            if (!current.skuId().equals(line.skuId()) || !current.unit().equalsIgnoreCase(line.unit())) throw error("DELIVERY_OUTCOME_LINE_MISMATCH");
            if (line.attemptedQuantity().compareTo(current.physicalRemainingQuantity()) > 0) throw error("DELIVERY_OUTCOME_EXCEEDS_REMAINING");
            if (request.outcome() == DeliveryAttemptOutcome.PARTIAL
                    && (line.rejectedQuantity().signum() > 0 || line.cancelledQuantity().signum() > 0)) {
                throw error("PARTIAL_OUTCOME_REQUIRES_CONTINUATION");
            }
            if (request.outcome() == DeliveryAttemptOutcome.PARTIAL && line.deliveredQuantity().signum() == 0) {
                throw error("PARTIAL_OUTCOME_REQUIRES_DELIVERY");
            }
        }
        if (request.outcome() != DeliveryAttemptOutcome.FAILED && uniqueLines.size() > currentLines.size()) throw error("DELIVERY_OUTCOME_LINE_INVALID");

        boolean coversAllPhysicalQuantity = currentLines.stream()
                .filter(line -> line.physicalRemainingQuantity().signum() > 0)
                .allMatch(line -> {
                    AttemptLine attempt = uniqueLines.get(line.id());
                    return attempt != null && attempt.attemptedQuantity().compareTo(line.physicalRemainingQuantity()) == 0;
                });
        if (request.outcome() == DeliveryAttemptOutcome.DELIVERED && !coversAllPhysicalQuantity) {
            throw error("DELIVERED_QUANTITY_INCOMPLETE");
        }
        if (isFinalQuantityResolution(request.outcome()) && !coversAllPhysicalQuantity) {
            throw error("FINAL_QUANTITY_INCOMPLETE");
        }

        Instant attemptedAt = request.attemptedAt() == null ? clock.instant() : request.attemptedAt();
        int attemptNumber = jdbc.queryForObject("select coalesce(max(attempt_number),0)+1 from logistics.delivery_attempt where tenant_id=? and workspace_id=? and delivery_id=?",
                Integer.class, request.tenantId(), request.workspaceId(), request.deliveryId());
        UUID attemptId = UUID.randomUUID();
        String attemptStatus = switch (request.outcome()) {
            case DELIVERED -> "FINAL";
            case PARTIAL -> "PARTIAL";
            case FAILED -> "FAILED";
            case REFUSED, ABSENT -> "REJECTED";
            case PENDING -> throw error("DELIVERY_OUTCOME_REQUIRED");
        };
        String failureReason = requiresReason(request.outcome()) ? boundedRequired(request.failureReason()) : bounded(request.failureReason());
        jdbc.update("insert into logistics.delivery_attempt(id,tenant_id,workspace_id,delivery_id,attempt_number,status,failure_reason,notes,occurred_at,created_at,outcome,attempted_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                attemptId, request.tenantId(), request.workspaceId(), request.deliveryId(), attemptNumber, attemptStatus,
                failureReason, bounded(request.notes()), Timestamp.from(attemptedAt), Timestamp.from(attemptedAt),
                request.outcome().name(), Timestamp.from(attemptedAt));

        BigDecimal finalAdjustment = BigDecimal.ZERO;
        String adjustmentCurrency = null;
        for (AttemptLine line : uniqueLines.values()) {
            FulfillmentLineRow current = byId.get(line.fulfillmentLineId());
            BigDecimal shortQuantity = line.rejectedQuantity().add(line.cancelledQuantity());
            String kind = lineKind(line);
            jdbc.update("insert into logistics.delivery_attempt_line(id,tenant_id,workspace_id,delivery_attempt_id,catalog_item_id,quantity,unit,created_at,fulfillment_line_id,sku_id,attempted_quantity,received_quantity) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), attemptId, current.catalogItemId(),
                    line.attemptedQuantity(), current.unit(), Timestamp.from(attemptedAt), line.fulfillmentLineId(),
                    line.skuId(), line.attemptedQuantity(), line.deliveredQuantity());
            jdbc.update("insert into logistics.delivery_quantity_outcome(id,tenant_id,workspace_id,delivery_id,delivery_attempt_id,fulfillment_line_id,sku_id,outcome,quantity,fulfilled_quantity,short_quantity,unit_price_amount,currency,reason,final_resolution,created_at,actor_membership_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.deliveryId(), attemptId,
                    line.fulfillmentLineId(), line.skuId(), kind, line.attemptedQuantity(), line.deliveredQuantity(),
                    shortQuantity, line.unitPriceAmount(), line.currency(), bounded(request.failureReason()),
                    isFinalQuantityResolution(request.outcome()), Timestamp.from(attemptedAt), request.actorMembershipId());
            if (jdbc.update("update logistics.fulfillment_line set delivered_quantity=delivered_quantity+?,rejected_quantity=rejected_quantity+?,cancelled_quantity=cancelled_quantity+? where tenant_id=? and workspace_id=? and id=? and delivered_quantity+rejected_quantity+cancelled_quantity+?<=dispatched_quantity",
                    line.deliveredQuantity(), line.rejectedQuantity(), line.cancelledQuantity(), request.tenantId(), request.workspaceId(), line.fulfillmentLineId(),
                    line.deliveredQuantity().add(line.rejectedQuantity()).add(line.cancelledQuantity())) != 1) {
                throw error("CONCURRENCY_CONFLICT");
            }
            if (isFinalQuantityResolution(request.outcome()) && shortQuantity.signum() > 0) {
                if (line.unitPriceAmount() == null || line.unitPriceAmount().signum() < 0 || line.currency() == null || line.currency().isBlank()) throw error("FINAL_QUANTITY_PRICE_REQUIRED");
                BigDecimal amount = line.unitPriceAmount().multiply(shortQuantity);
                finalAdjustment = finalAdjustment.add(amount);
                if (adjustmentCurrency == null) adjustmentCurrency = line.currency().trim().toUpperCase(java.util.Locale.ROOT);
                if (!adjustmentCurrency.equalsIgnoreCase(line.currency())) throw error("FINAL_QUANTITY_CURRENCY_MISMATCH");
            }
        }

        List<RemainingLine> remaining = physicalRemaining(request.tenantId(), request.workspaceId(), fulfillment.id());
        boolean allPhysicalResolved = remaining.isEmpty();
        boolean allDelivered = allPhysicalResolved && uniqueLines.values().stream().allMatch(line -> line.rejectedQuantity().signum() == 0 && line.cancelledQuantity().signum() == 0);
        String targetDeliveryStatus = switch (request.outcome()) {
            case DELIVERED -> allDelivered ? "DELIVERED" : "FAILED";
            case PARTIAL -> allPhysicalResolved ? (allDelivered ? "DELIVERED" : "FAILED") : "PARTIAL";
            case FAILED, REFUSED, ABSENT -> allPhysicalResolved ? (allDelivered ? "DELIVERED" : "FAILED") : "FAILED";
            case PENDING -> throw error("DELIVERY_OUTCOME_REQUIRED");
        };
        if (jdbc.update("update logistics.delivery set status=?,updated_at=?,delivered_at=case when ?='DELIVERED' then ? else delivered_at end,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                targetDeliveryStatus, Timestamp.from(attemptedAt), targetDeliveryStatus, Timestamp.from(attemptedAt),
                request.tenantId(), request.workspaceId(), request.deliveryId(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        boolean allCommercialResolved = commercialRemaining(request.tenantId(), request.workspaceId(), fulfillment.id()).isEmpty();
        if (allCommercialResolved) {
            if (jdbc.update("update logistics.fulfillment set status='COMPLETED',completed_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                    Timestamp.from(attemptedAt), Timestamp.from(attemptedAt), request.tenantId(), request.workspaceId(), fulfillment.id(), fulfillment.version()) != 1) {
                throw error("CONCURRENCY_CONFLICT");
            }
        }
        insertDeliveryEvent(request.tenantId(), request.workspaceId(), request.deliveryId(), "DELIVERY_ATTEMPT_RECORDED",
                request.actorMembershipId(), bounded(request.failureReason()), attemptedAt);
        if ("DELIVERED".equals(targetDeliveryStatus)) {
            CanonicalOutbox.append(jdbc, "DeliveryCompleted.v1", "Delivery", request.deliveryId(), request.tenantId(),
                    request.workspaceId(), attemptedAt, request.idempotencyKey(), null, "1.0", request.idempotencyKey(),
                    Map.of("deliveryId", request.deliveryId(), "fulfillmentId", fulfillment.id(), "attemptId", attemptId));
        }
        if (request.outcome() != DeliveryAttemptOutcome.FAILED && !allPhysicalResolved) {
            createContinuation(request, delivery, fulfillment, remaining, attemptedAt);
        }
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "ATTEMPT",
                request.idempotencyKey(), request.requestHash(), attemptId, attemptedAt);
        return new DeliveryOutcomeResult(load(request.tenantId(), request.workspaceId(), request.deliveryId()), attemptId,
                finalAdjustment, adjustmentCurrency, null, delivery.salesOrderId(), allCommercialResolved,
                !allCommercialResolved, remaining);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PodView capturePod(PodRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "POD_CAPTURE", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "POD_CAPTURE", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return loadPod(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (!"DELIVERED".equals(delivery.status())) throw error("POD_REQUIRES_FINAL_DELIVERY");
        if (delivery.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT");
        String receiver = boundedRequired(request.receiverName());
        if (request.photoEvidenceObjectId() == null && request.signatureEvidenceObjectId() == null) throw error("POD_EVIDENCE_REQUIRED");
        UUID podId = UUID.randomUUID();
        UUID attemptId = jdbc.query("select id from logistics.delivery_attempt where tenant_id=? and workspace_id=? and delivery_id=? order by attempt_number desc,id desc limit 1",
                (rs, row) -> rs.getObject(1, UUID.class), request.tenantId(), request.workspaceId(), request.deliveryId()).stream().findFirst().orElse(null);
        Instant capturedAt = request.capturedAt() == null ? clock.instant() : request.capturedAt();
        jdbc.update("insert into logistics.proof_of_delivery(id,tenant_id,workspace_id,dispatch_order_id,receiver_name,completed_at,notes,photo_evidence_declared,signature_evidence_declared,status,created_at,delivery_id,attempt_id,photo_evidence_object_id,signature_evidence_object_id,sealed_at) values (?,?,?,?,?,?,?,?,?,'CAPTURED',?,?,?,?,?,null)",
                podId, request.tenantId(), request.workspaceId(), null, receiver, Timestamp.from(capturedAt), bounded(request.notes()),
                request.photoEvidenceObjectId() != null, request.signatureEvidenceObjectId() != null, Timestamp.from(capturedAt),
                request.deliveryId(), attemptId, request.photoEvidenceObjectId(), request.signatureEvidenceObjectId());
        if (jdbc.update("update logistics.delivery set updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                Timestamp.from(capturedAt), request.tenantId(), request.workspaceId(), request.deliveryId(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        insertDeliveryEvent(request.tenantId(), request.workspaceId(), request.deliveryId(), "POD_CAPTURED",
                request.actorMembershipId(), "Proof of delivery captured", capturedAt);
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "POD_CAPTURE",
                request.idempotencyKey(), request.requestHash(), podId, capturedAt);
        return loadPod(request.tenantId(), request.workspaceId(), podId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PodView sealPod(PodSealRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "POD_SEAL", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "POD_SEAL", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return loadPod(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT");
        PodRow pod = jdbc.query("select id,delivery_id,status from logistics.proof_of_delivery where tenant_id=? and workspace_id=? and delivery_id=? for update",
                (rs, row) -> new PodRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class), rs.getString("status")),
                request.tenantId(), request.workspaceId(), request.deliveryId()).stream().findFirst().orElseThrow(() -> error("POD_NOT_FOUND"));
        if (!"CAPTURED".equals(pod.status())) throw error("POD_NOT_CAPTURED");
        Instant sealedAt = request.sealedAt() == null ? clock.instant() : request.sealedAt();
        if (jdbc.update("update logistics.proof_of_delivery set status='SEALED',sealed_at=? where tenant_id=? and workspace_id=? and id=? and status='CAPTURED'",
                Timestamp.from(sealedAt), request.tenantId(), request.workspaceId(), pod.id()) != 1) throw error("CONCURRENCY_CONFLICT");
        if (jdbc.update("update logistics.delivery set updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                Timestamp.from(sealedAt), request.tenantId(), request.workspaceId(), request.deliveryId(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        insertDeliveryEvent(request.tenantId(), request.workspaceId(), request.deliveryId(), "POD_SEALED",
                request.actorMembershipId(), "Proof of delivery sealed", sealedAt);
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "POD_SEAL",
                request.idempotencyKey(), request.requestHash(), pod.id(), sealedAt);
        return loadPod(request.tenantId(), request.workspaceId(), pod.id());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TemperatureView recordTemperature(TemperatureRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        if (request.temperatureCelsius() == null || request.temperatureCelsius().compareTo(BigDecimal.valueOf(-1000)) <= 0
                || request.temperatureCelsius().compareTo(BigDecimal.valueOf(1000)) >= 0) throw error("TEMPERATURE_VALUE_INVALID");
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "TEMPERATURE", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "TEMPERATURE", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return loadTemperature(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT");
        if ("CANCELLED".equals(delivery.status())) throw error("DELIVERY_NOT_ACTIVE");
        String unit = request.unit() == null || request.unit().isBlank() ? "CELSIUS" : request.unit().trim().toUpperCase(java.util.Locale.ROOT);
        String status = classify(request.tenantId(), request.workspaceId(), request.deliveryId(), request.temperatureCelsius(), unit);
        Instant recordedAt = request.recordedAt() == null ? clock.instant() : request.recordedAt();
        UUID evidenceId = UUID.randomUUID();
        jdbc.update("insert into logistics.temperature_evidence(id,tenant_id,workspace_id,delivery_id,lot_id,value,temperature_celsius,unit,recorded_at,source,evidence_metadata,status,evidence_object_id,actor_membership_id,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                evidenceId, request.tenantId(), request.workspaceId(), request.deliveryId(), request.lotId(), request.temperatureCelsius(),
                request.temperatureCelsius(), unit, Timestamp.from(recordedAt), boundedOrDefault(request.source(), "MANUAL"),
                bounded(request.evidenceMetadata()), status, null, request.actorMembershipId(), Timestamp.from(recordedAt));
        if (jdbc.update("update logistics.delivery set updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                Timestamp.from(recordedAt), request.tenantId(), request.workspaceId(), request.deliveryId(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        if ("OUT_OF_RANGE".equals(status)) {
            jdbc.update("insert into logistics.temperature_excursion(id,tenant_id,workspace_id,delivery_id,lot_id,temperature_evidence_id,status,disposition,affected_quantity,threshold,reason,actor_membership_id,created_at) values (?,?,?,?,?,?, 'OPEN','HOLD',null,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.deliveryId(), request.lotId(), evidenceId,
                    "Configured cold-chain range", "Temperature evidence is outside the configured range", request.actorMembershipId(), Timestamp.from(recordedAt));
        }
        insertDeliveryEvent(request.tenantId(), request.workspaceId(), request.deliveryId(), "TEMPERATURE_EVIDENCE_RECORDED",
                request.actorMembershipId(), status, recordedAt);
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "TEMPERATURE",
                request.idempotencyKey(), request.requestHash(), evidenceId, recordedAt);
        return loadTemperature(request.tenantId(), request.workspaceId(), evidenceId);
    }

    private DeliveryView load(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        DeliveryRow delivery = jdbc.query("select d.id,d.fulfillment_id,f.sales_order_id,d.status,d.destination_snapshot,d.scheduled_at,d.dispatched_at,d.delivered_at,d.version from logistics.delivery d left join logistics.fulfillment f on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id where d.tenant_id=? and d.workspace_id=? and d.id=?",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getObject("fulfillment_id", UUID.class),
                        rs.getObject("sales_order_id", UUID.class), rs.getString("status"), rs.getString("destination_snapshot"),
                        instant(rs, "scheduled_at"), instant(rs, "dispatched_at"), instant(rs, "delivered_at"), rs.getLong("version")),
                tenantId, workspaceId, deliveryId).stream().findFirst().orElseThrow(() -> error("DELIVERY_NOT_FOUND"));
        List<AttemptView> attempts = jdbc.query("select id,attempt_number,outcome,status,failure_reason,notes,attempted_at from logistics.delivery_attempt where tenant_id=? and workspace_id=? and delivery_id=? order by attempt_number desc,id desc",
                (rs, row) -> new AttemptView(rs.getObject("id", UUID.class), rs.getInt("attempt_number"), rs.getString("outcome"),
                        rs.getString("status"), rs.getString("failure_reason"), rs.getString("notes"), instant(rs, "attempted_at")),
                tenantId, workspaceId, deliveryId);
        return new DeliveryView(delivery.id(), delivery.fulfillmentId(), delivery.salesOrderId(), delivery.status(),
                delivery.destinationSnapshot(), delivery.scheduledAt(), delivery.dispatchedAt(), delivery.deliveredAt(),
                delivery.version(), attempts);
    }

    private DeliveryOutcomeResult replayResult(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID attemptId) {
        DeliveryView delivery = load(tenantId, workspaceId, deliveryId);
        BigDecimal amount = jdbc.queryForObject("select coalesce(sum(short_quantity * coalesce(unit_price_amount,0)),0) from logistics.delivery_quantity_outcome where tenant_id=? and workspace_id=? and delivery_attempt_id=? and final_resolution=true",
                BigDecimal.class, tenantId, workspaceId, attemptId);
        String currency = jdbc.query("select currency from logistics.delivery_quantity_outcome where tenant_id=? and workspace_id=? and delivery_attempt_id=? and final_resolution=true and currency is not null order by id limit 1",
                (rs, row) -> rs.getString(1), tenantId, workspaceId, attemptId).stream().findFirst().orElse(null);
        List<RemainingLine> remaining = delivery.fulfillmentId() == null ? List.of() : physicalRemaining(tenantId, workspaceId, delivery.fulfillmentId());
        boolean allCommercialResolved = delivery.fulfillmentId() == null
                || commercialRemaining(tenantId, workspaceId, delivery.fulfillmentId()).isEmpty();
        return new DeliveryOutcomeResult(delivery, attemptId, amount, currency, null, delivery.salesOrderId(),
                allCommercialResolved, "PARTIAL".equals(delivery.status()), remaining);
    }

    private List<RemainingLine> physicalRemaining(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        return jdbc.query("select id,sku_id,catalog_item_id,greatest(dispatched_quantity-delivered_quantity-rejected_quantity-cancelled_quantity,0) remaining_quantity,unit from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? and dispatched_quantity-delivered_quantity-rejected_quantity-cancelled_quantity>0 order by id",
                (rs, row) -> new RemainingLine(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getBigDecimal("remaining_quantity"), rs.getString("unit")),
                tenantId, workspaceId, fulfillmentId);
    }

    private List<RemainingLine> commercialRemaining(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        return jdbc.query("select id,sku_id,catalog_item_id,remaining_quantity,unit from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? and remaining_quantity>0 order by id",
                (rs, row) -> new RemainingLine(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getBigDecimal("remaining_quantity"), rs.getString("unit")),
                tenantId, workspaceId, fulfillmentId);
    }

    private void createContinuation(AttemptRequest request, DeliveryRow delivery, FulfillmentRow fulfillment,
                                    List<RemainingLine> remaining, Instant now) {
        UUID continuationId = UUID.nameUUIDFromBytes((request.tenantId() + "|" + request.workspaceId() + "|"
                + request.deliveryId() + "|continuation").getBytes(StandardCharsets.UTF_8));
        String snapshot;
        try { snapshot = mapper.writeValueAsString(remaining); }
        catch (Exception exception) { throw new IllegalStateException("Continuation snapshot could not be serialized", exception); }
        int inserted = jdbc.update("insert into logistics.continuation_delivery(id,tenant_id,workspace_id,source_delivery_id,sales_order_id,client_account_id,status,created_at,updated_at,version,parent_delivery_id,remaining_snapshot,opened_at,idempotency_key,request_hash) values (?,?,?,null,?,?,'OPEN',?,?,0,?,?,?, ?,?) on conflict do nothing",
                continuationId, request.tenantId(), request.workspaceId(), fulfillment.salesOrderId(), fulfillment.clientAccountId(),
                Timestamp.from(now), Timestamp.from(now), request.deliveryId(), snapshot, Timestamp.from(now),
                request.idempotencyKey(), request.requestHash());
        if (inserted == 1) {
            for (RemainingLine line : remaining) {
                jdbc.update("insert into logistics.continuation_delivery_line(id,tenant_id,workspace_id,continuation_delivery_id,catalog_item_id,quantity,unit,fulfillment_line_id,sku_id,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), request.tenantId(), request.workspaceId(), continuationId, line.catalogItemId(),
                        line.quantity(), line.unit(), line.fulfillmentLineId(), line.skuId(), Timestamp.from(now));
            }
            CanonicalOutbox.append(jdbc, "ContinuationDeliveryCreated.v1", "ContinuationDelivery", continuationId,
                    request.tenantId(), request.workspaceId(), now, request.idempotencyKey(), null, "1.0",
                    request.idempotencyKey(), Map.of("continuationDeliveryId", continuationId, "sourceDeliveryId", request.deliveryId(),
                            "remainingLines", remaining));
        }
    }

    private PodView loadPod(UUID tenantId, UUID workspaceId, UUID podId) {
        return jdbc.query("select p.id,p.delivery_id,p.status,p.receiver_name,p.completed_at,p.sealed_at,p.photo_evidence_object_id,p.signature_evidence_object_id,d.version delivery_version from logistics.proof_of_delivery p left join logistics.delivery d on d.tenant_id=p.tenant_id and d.workspace_id=p.workspace_id and d.id=p.delivery_id where p.tenant_id=? and p.workspace_id=? and p.id=?",
                (rs, row) -> new PodView(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class), rs.getString("status"),
                        rs.getString("receiver_name"), instant(rs, "completed_at"), instant(rs, "sealed_at"),
                        rs.getObject("photo_evidence_object_id", UUID.class), rs.getObject("signature_evidence_object_id", UUID.class),
                        number(rs, "delivery_version")),
                tenantId, workspaceId, podId).stream().findFirst().orElseThrow(() -> error("POD_NOT_FOUND"));
    }

    private TemperatureView loadTemperature(UUID tenantId, UUID workspaceId, UUID evidenceId) {
        return jdbc.query("select t.id,t.delivery_id,t.lot_id,t.temperature_celsius,t.unit,t.source,t.status,t.recorded_at,d.version delivery_version from logistics.temperature_evidence t join logistics.delivery d on d.tenant_id=t.tenant_id and d.workspace_id=t.workspace_id and d.id=t.delivery_id where t.tenant_id=? and t.workspace_id=? and t.id=?",
                (rs, row) -> new TemperatureView(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("lot_id", UUID.class), rs.getBigDecimal("temperature_celsius"), rs.getString("unit"),
                        rs.getString("source"), rs.getString("status"), instant(rs, "recorded_at"), number(rs, "delivery_version")),
                tenantId, workspaceId, evidenceId).stream().findFirst().orElseThrow(() -> error("TEMPERATURE_EVIDENCE_NOT_FOUND"));
    }

    private String classify(UUID tenantId, UUID workspaceId, UUID deliveryId, BigDecimal value, String unit) {
        if (!"CELSIUS".equals(unit)) return "UNKNOWN";
        return coldChain.rangeForDelivery(tenantId, workspaceId, deliveryId)
                .map(range -> value.compareTo(range.minimumCelsius()) >= 0 && value.compareTo(range.maximumCelsius()) <= 0
                        ? "WITHIN_RANGE" : "OUT_OF_RANGE")
                .orElse("UNKNOWN");
    }

    private DeliveryRow lockDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        return jdbc.query("select id,fulfillment_id,(select f.sales_order_id from logistics.fulfillment f where f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id) sales_order_id,status,destination_snapshot,scheduled_at,dispatched_at,delivered_at,version from logistics.delivery d where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getObject("fulfillment_id", UUID.class),
                        rs.getObject("sales_order_id", UUID.class), rs.getString("status"), rs.getString("destination_snapshot"),
                        instant(rs, "scheduled_at"), instant(rs, "dispatched_at"), instant(rs, "delivered_at"), rs.getLong("version")),
                tenantId, workspaceId, deliveryId).stream().findFirst().orElseThrow(() -> error("DELIVERY_NOT_FOUND"));
    }

    private FulfillmentRow lockFulfillment(UUID tenantId, UUID workspaceId, UUID fulfillmentId, UUID clientAccountId) {
        return jdbc.query("select id,sales_order_id,version,status from logistics.fulfillment where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new FulfillmentRow(rs.getObject("id", UUID.class), rs.getObject("sales_order_id", UUID.class),
                        clientAccountId, rs.getLong("version"), rs.getString("status")),
                tenantId, workspaceId, fulfillmentId).stream().findFirst().orElseThrow(() -> error("FULFILLMENT_NOT_FOUND"));
    }

    private List<FulfillmentLineRow> lockFulfillmentLines(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        return jdbc.query("select id,sku_id,catalog_item_id,unit,dispatched_quantity,delivered_quantity,rejected_quantity,cancelled_quantity,unfulfilled_quantity,remaining_quantity from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? order by id for update",
                (rs, row) -> new FulfillmentLineRow(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getString("unit"), rs.getBigDecimal("dispatched_quantity"),
                        rs.getBigDecimal("delivered_quantity"), rs.getBigDecimal("rejected_quantity"),
                        rs.getBigDecimal("cancelled_quantity"), rs.getBigDecimal("unfulfilled_quantity"),
                        rs.getBigDecimal("remaining_quantity")),
                tenantId, workspaceId, fulfillmentId);
    }

    private void insertDeliveryEvent(UUID tenantId, UUID workspaceId, UUID deliveryId, String eventType,
                                     UUID actorMembershipId, String reason, Instant occurredAt) {
        jdbc.update("insert into logistics.delivery_event(id,tenant_id,workspace_id,delivery_id,event_type,actor_membership_id,reason,occurred_at) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, deliveryId, eventType, actorMembershipId, bounded(reason), Timestamp.from(occurredAt));
    }

    private IdempotencyRow idempotency(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key) {
        return jdbc.query("select request_hash,resource_id from logistics.delivery_command_idempotency where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=? for update",
                (rs, row) -> new IdempotencyRow(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)),
                tenantId, workspaceId, actor, operation, key).stream().findFirst().orElse(null);
    }

    private void insertIdempotency(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key,
                                   String hash, UUID resourceId, Instant now) {
        jdbc.update("insert into logistics.delivery_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                tenantId, workspaceId, actor, operation, key, hash, resourceId, Timestamp.from(now));
    }

    private void lockCommand(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenantId + "|" + workspaceId + "|delivery|" + actor + "|" + operation + "|" + key);
    }

    private static void validateScope(UUID tenantId, UUID workspaceId, UUID actor, String key) {
        if (tenantId == null || workspaceId == null || actor == null || key == null || key.isBlank()) throw error("INVALID_REQUEST");
    }

    private static void validateAttemptLine(AttemptLine line, DeliveryAttemptOutcome outcome) {
        if (line == null || line.fulfillmentLineId() == null || line.skuId() == null || line.attemptedQuantity() == null
                || line.attemptedQuantity().signum() <= 0 || line.deliveredQuantity() == null || line.deliveredQuantity().signum() < 0
                || line.rejectedQuantity() == null || line.rejectedQuantity().signum() < 0 || line.cancelledQuantity() == null
                || line.cancelledQuantity().signum() < 0 || line.unit() == null || line.unit().isBlank()
                || line.deliveredQuantity().add(line.rejectedQuantity()).add(line.cancelledQuantity()).compareTo(line.attemptedQuantity()) != 0) {
            throw error("DELIVERY_OUTCOME_LINE_INVALID");
        }
        if (outcome == DeliveryAttemptOutcome.DELIVERED && line.deliveredQuantity().compareTo(line.attemptedQuantity()) != 0) throw error("DELIVERED_QUANTITY_INCOMPLETE");
        if (outcome == DeliveryAttemptOutcome.REFUSED || outcome == DeliveryAttemptOutcome.ABSENT) {
            if (line.deliveredQuantity().signum() > 0) throw error("DELIVERY_OUTCOME_LINE_INVALID");
            if (line.deliveredQuantity().add(line.rejectedQuantity()).add(line.cancelledQuantity()).compareTo(line.attemptedQuantity()) != 0) throw error("FINAL_QUANTITY_INCOMPLETE");
        }
    }

    private static String lineKind(AttemptLine line) {
        if (line.deliveredQuantity().compareTo(line.attemptedQuantity()) == 0) return "DELIVERED";
        if (line.deliveredQuantity().signum() > 0) return "PARTIAL";
        if (line.rejectedQuantity().signum() > 0) return "REJECTED";
        return "UNDELIVERED";
    }

    private static boolean isFinalQuantityResolution(DeliveryAttemptOutcome outcome) {
        return outcome == DeliveryAttemptOutcome.REFUSED || outcome == DeliveryAttemptOutcome.ABSENT;
    }

    private static boolean requiresReason(DeliveryAttemptOutcome outcome) {
        return outcome == DeliveryAttemptOutcome.FAILED || outcome == DeliveryAttemptOutcome.REFUSED || outcome == DeliveryAttemptOutcome.ABSENT;
    }

    private static void requireDeliveryTransition(String current, String target) {
        boolean allowed = switch (target) {
            case "IN_TRANSIT" -> "DISPATCHED".equals(current) || "FAILED".equals(current);
            case "ASSIGNED" -> "PLANNED".equals(current);
            case "CANCELLED" -> !SetOfTerminalDeliveryStatuses.contains(current);
            default -> false;
        };
        if (!allowed) throw error("DELIVERY_TRANSITION_INVALID");
    }

    private static void ensureHash(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000);
    }

    private static String boundedRequired(String value) {
        String result = bounded(value);
        if (result == null) throw error("FAILURE_REASON_REQUIRED");
        return result;
    }

    private static String boundedOrDefault(String value, String fallback) {
        String result = bounded(value);
        return result == null ? fallback : result;
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static long number(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? -1L : value.longValue();
    }

    private static FulfillmentOperationException error(String code) {
        return new FulfillmentOperationException(code, code != null && code.endsWith("_NOT_FOUND"));
    }

    private static final java.util.Set<String> SetOfTerminalDeliveryStatuses = java.util.Set.of("DELIVERED", "CANCELLED");
    private static final java.util.Set<String> SetOfAttemptableDeliveryStatuses = java.util.Set.of("IN_TRANSIT", "PARTIAL");

    private record IdempotencyRow(String requestHash, UUID resourceId) { }
    private record PodRow(UUID id, UUID deliveryId, String status) { }
    private record FulfillmentLineRow(UUID id, UUID skuId, String catalogItemId, String unit,
                                      BigDecimal dispatchedQuantity, BigDecimal deliveredQuantity,
                                      BigDecimal rejectedQuantity, BigDecimal cancelledQuantity,
                                      BigDecimal unfulfilledQuantity, BigDecimal remainingQuantity) {
        private BigDecimal physicalRemainingQuantity() {
            return dispatchedQuantity.subtract(deliveredQuantity).subtract(rejectedQuantity).subtract(cancelledQuantity).max(BigDecimal.ZERO);
        }
    }
    private record FulfillmentRow(UUID id, UUID salesOrderId, UUID clientAccountId, long version, String status) { }
    private record DeliveryRow(UUID id, UUID fulfillmentId, UUID salesOrderId, String status, String destinationSnapshot,
                               Instant scheduledAt, Instant dispatchedAt, Instant deliveredAt, long version) { }
}
