package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.FulfillmentView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.LineView;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** SQL adapter for the BC-06 fulfillment aggregate and quantity ledger. */
@Repository
@Profile("!test")
public class JdbcFulfillmentLifecycleAdapter implements FulfillmentPersistencePort {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PhysicalAllocationCommands physicalAllocations;

    public JdbcFulfillmentLifecycleAdapter(JdbcTemplate jdbc, Clock clock, PhysicalAllocationCommands physicalAllocations) {
        this.jdbc = jdbc;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.physicalAllocations = Objects.requireNonNull(physicalAllocations, "Physical allocations are required");
    }

    @Override
    public FulfillmentView find(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        return load(tenantId, workspaceId, fulfillmentId);
    }

    @Override
    public FulfillmentView findBySalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return jdbc.query("select id from logistics.fulfillment where tenant_id=? and workspace_id=? and sales_order_id=? order by created_at desc,id desc limit 1",
                        (rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, salesOrderId)
                .stream().findFirst().map(id -> load(tenantId, workspaceId, id)).orElse(null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FulfillmentView createAllocated(CreateRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "START", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "START", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        if (findBySalesOrder(request.tenantId(), request.workspaceId(), request.salesOrderId()) != null) {
            throw error("FULFILLMENT_ALREADY_EXISTS");
        }
        if (request.lines() == null || request.lines().isEmpty()) throw error("FULFILLMENT_LINES_REQUIRED");
        Instant now = Objects.requireNonNull(request.now(), "now");
        jdbc.update("insert into logistics.fulfillment(id,tenant_id,workspace_id,sales_order_id,physical_allocation_id,status,destination_snapshot,planned_at,created_at,updated_at,allocated_at,version,actor_membership_id) values (?,?,?,?,?,'ALLOCATED',?,?,?,?,?,0,?)",
                request.fulfillmentId(), request.tenantId(), request.workspaceId(), request.salesOrderId(),
                request.physicalAllocationId(), request.destinationSnapshot(), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), request.actorMembershipId());
        for (CreateLine line : request.lines()) {
            validateLine(line);
            jdbc.update("insert into logistics.fulfillment_line(id,tenant_id,workspace_id,fulfillment_id,sku_id,physical_allocation_id,catalog_item_id,ordered_quantity,backed_quantity,allocated_quantity,unit,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                    line.id(), request.tenantId(), request.workspaceId(), request.fulfillmentId(), line.skuId(),
                    request.physicalAllocationId(), line.catalogItemId(), line.orderedQuantity(), line.backedQuantity(),
                    line.allocatedQuantity(), line.unit(), Timestamp.from(now));
        }
        insertEvent(request.tenantId(), request.workspaceId(), request.fulfillmentId(), null, "ALLOCATED",
                "FULFILLMENT_ALLOCATED", request.actorMembershipId(), "Physical allocation accepted", now);
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "START",
                request.idempotencyKey(), request.requestHash(), request.fulfillmentId(), now);
        return load(request.tenantId(), request.workspaceId(), request.fulfillmentId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FulfillmentView transition(TransitionRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(), request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(), request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        FulfillmentRow current = lockFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        requireTransition(current.status(), request.targetStatus());
        Instant now = Objects.requireNonNull(request.now(), "now");
        String timestampColumn = switch (request.targetStatus()) {
            case "PICKING" -> "started_at";
            case "PACKED" -> "packed_at";
            case "STAGED" -> "staged_at";
            case "READY_FOR_DISPATCH" -> null;
            case "HOLD", "CANCELLED" -> null;
            default -> throw error("FULFILLMENT_TRANSITION_INVALID");
        };
        if ("PACKED".equals(request.targetStatus())) {
            jdbc.update("update logistics.fulfillment_line set packed_quantity=picked_quantity where tenant_id=? and workspace_id=? and fulfillment_id=?",
                    request.tenantId(), request.workspaceId(), request.fulfillmentId());
        } else if ("STAGED".equals(request.targetStatus())) {
            jdbc.update("update logistics.fulfillment_line set staged_quantity=packed_quantity where tenant_id=? and workspace_id=? and fulfillment_id=?",
                    request.tenantId(), request.workspaceId(), request.fulfillmentId());
        } else if ("READY_FOR_DISPATCH".equals(request.targetStatus())
                && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? and staged_quantity<>packed_quantity)",
                Boolean.class, request.tenantId(), request.workspaceId(), request.fulfillmentId()))) {
            throw error("FULFILLMENT_LINES_NOT_STAGED");
        }
        String timestampUpdate = timestampColumn == null ? "" : "," + timestampColumn + "=?";
        List<Object> args = new ArrayList<>();
        args.add(request.targetStatus()); args.add(Timestamp.from(now));
        if (timestampColumn != null) args.add(Timestamp.from(now));
        args.add(request.tenantId()); args.add(request.workspaceId()); args.add(request.fulfillmentId()); args.add(request.expectedVersion());
        if (jdbc.update("update logistics.fulfillment set status=?,updated_at=?,version=version+1" + timestampUpdate + " where tenant_id=? and workspace_id=? and id=? and version=?",
                args.toArray()) != 1) throw error("CONCURRENCY_CONFLICT");
        insertEvent(request.tenantId(), request.workspaceId(), request.fulfillmentId(), current.status(), request.targetStatus(),
                request.operation(), request.actorMembershipId(), bounded(request.reason()), now);
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(),
                request.idempotencyKey(), request.requestHash(), request.fulfillmentId(), now);
        return load(request.tenantId(), request.workspaceId(), request.fulfillmentId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FulfillmentView confirmPicking(PickingRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PICK_CONFIRM", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PICK_CONFIRM", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        if (hasPhysicalPickingBinding(request)) {
            physicalAllocations.lockForFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        }
        FulfillmentRow current = lockFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        if (!"PICKING".equals(current.status())) throw error("FULFILLMENT_PICKING_REQUIRED");
        List<FulfillmentLineRow> currentLines = lockLines(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        Map<UUID, List<PickedLine>> requested = new HashMap<>();
        for (PickedLine line : request.lines() == null ? List.<PickedLine>of() : request.lines()) {
            if (line == null || line.fulfillmentLineId() == null || line.skuId() == null || line.quantity() == null
                    || line.quantity().signum() < 0 || line.unit() == null || line.unit().isBlank()) {
                throw error("FULFILLMENT_PICKING_LINES_INVALID");
            }
            requested.computeIfAbsent(line.fulfillmentLineId(), ignored -> new ArrayList<>()).add(line);
        }
        if (requested.size() != currentLines.size()) throw error("FULFILLMENT_PICKING_LINES_INCOMPLETE");
        if (isMixedPickingMode(request.lines() == null ? List.of() : request.lines())) {
            throw error("PHYSICAL_SCAN_REFERENCE_REQUIRED");
        }
        if (containsDuplicateLegacyLines(requested)) {
            throw error("FULFILLMENT_PICKING_LINES_INVALID");
        }
        Instant validationNow = clock.instant();
        Long allocationVersion = request.allocationVersion();
        boolean shortage = false;
        Set<UUID> physicalAllocationLineIds = new HashSet<>();
        Map<UUID, BigDecimal> pickedQuantities = new HashMap<>();
        for (FulfillmentLineRow line : currentLines) {
            List<PickedLine> pickedLines = requested.get(line.id());
            if (pickedLines == null || pickedLines.isEmpty()) throw error("FULFILLMENT_PICKING_LINES_INCOMPLETE");
            BigDecimal pickedQuantity = BigDecimal.ZERO;
            for (PickedLine picked : pickedLines) {
                if (!line.skuId().equals(picked.skuId()) || !line.unit().equalsIgnoreCase(picked.unit())) {
                    throw error("FULFILLMENT_PICKING_QUANTITY_INVALID");
                }
                pickedQuantity = pickedQuantity.add(picked.quantity());
            }
            if (pickedQuantity.compareTo(line.allocatedQuantity()) > 0) {
                throw error("FULFILLMENT_PICKING_QUANTITY_INVALID");
            }
            pickedQuantities.put(line.id(), pickedQuantity);
            for (PickedLine picked : pickedLines) {
                boolean hasPhysicalReference = picked.physicalAllocationLineId() != null || picked.lotId() != null
                        || picked.warehouseId() != null;
                if (picked.fefoOverride() && !hasPhysicalReference) throw error("OVERRIDE_NOT_ALLOWED");
                if (hasPhysicalReference) {
                    if (picked.quantity().signum() <= 0 || picked.physicalAllocationLineId() == null
                            || picked.lotId() == null || picked.warehouseId() == null || allocationVersion == null) {
                        throw error("PHYSICAL_SCAN_REFERENCE_REQUIRED");
                    }
                    if (!physicalAllocationLineIds.add(picked.physicalAllocationLineId())) {
                        throw error("FULFILLMENT_PICKING_LINES_INVALID");
                    }
                    PhysicalAllocationCommands.PickingScanValidationResult validation = physicalAllocations.validatePickingScan(
                            new PhysicalAllocationCommands.PickingScanValidationRequest(request.tenantId(), request.workspaceId(),
                                    request.fulfillmentId(), picked.physicalAllocationLineId(), picked.skuId(), picked.lotId(),
                                    picked.warehouseId(), picked.quantity(), picked.unit(), allocationVersion, validationNow,
                                    request.actorMembershipId(), picked.fefoOverride(), picked.fefoOverrideReason()));
                    if (!"MATCH".equals(validation.outcome()) && !"ALLOWED_OVERRIDE".equals(validation.outcome())) {
                        throw error(validation.outcome());
                    }
                    allocationVersion = validation.allocationVersion();
                }
            }
            shortage |= pickedQuantity.compareTo(line.allocatedQuantity()) < 0;
        }
        Instant now = request.completedAt() == null ? (request.startedAt() == null ? clock.instant() : request.startedAt()) : request.completedAt();
        UUID resultId = UUID.randomUUID();
        String resultStatus = shortage ? "DISCREPANCY" : "CONFIRMED";
        jdbc.update("insert into logistics.picking_result(id,tenant_id,workspace_id,fulfillment_id,status,actor_membership_id,picker_identity_id,started_at,completed_at,idempotency_key,request_hash,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                resultId, request.tenantId(), request.workspaceId(), request.fulfillmentId(), resultStatus,
                request.actorMembershipId(), request.pickerIdentityId() == null ? request.actorMembershipId() : request.pickerIdentityId(),
                Timestamp.from(request.startedAt() == null ? now : request.startedAt()), Timestamp.from(now),
                request.idempotencyKey(), request.requestHash(), Timestamp.from(now));
        for (FulfillmentLineRow line : currentLines) {
            List<PickedLine> pickedLines = requested.get(line.id());
            BigDecimal pickedQuantity = pickedQuantities.get(line.id());
            for (PickedLine picked : pickedLines) {
                if (picked.quantity().signum() <= 0) continue;
                jdbc.update("insert into logistics.picking_result_line(id,tenant_id,workspace_id,picking_result_id,fulfillment_line_id,quantity,unit,physical_allocation_line_id,lot_id,warehouse_id,created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), request.tenantId(), request.workspaceId(), resultId, line.id(), picked.quantity(), line.unit(),
                        picked.physicalAllocationLineId(), picked.lotId(), picked.warehouseId(), Timestamp.from(now));
            }
            jdbc.update("update logistics.fulfillment_line set picked_quantity=? where tenant_id=? and workspace_id=? and id=?",
                    pickedQuantity, request.tenantId(), request.workspaceId(), line.id());
            if (pickedQuantity.compareTo(line.allocatedQuantity()) < 0) {
                jdbc.update("insert into logistics.picking_discrepancy(id,tenant_id,workspace_id,fulfillment_id,fulfillment_line_id,picking_result_id,kind,quantity,reason,actor_membership_id,created_at) values (?,?,?,?,?,?, 'SHORTAGE',?,?,?,?)",
                        UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.fulfillmentId(), line.id(), resultId,
                        line.allocatedQuantity().subtract(pickedQuantity), bounded(request.notes() == null ? "Picking shortage" : request.notes()), request.actorMembershipId(), Timestamp.from(now));
            }
        }
        String target = shortage ? "SHORTAGE" : "PICKED";
        if (jdbc.update("update logistics.fulfillment set status=?,picked_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                target, Timestamp.from(now), Timestamp.from(now), request.tenantId(), request.workspaceId(), request.fulfillmentId(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        insertEvent(request.tenantId(), request.workspaceId(), request.fulfillmentId(), current.status(), target,
                shortage ? "FULFILLMENT_SHORTAGE" : "PICKING_CONFIRMED", request.actorMembershipId(), bounded(request.notes()), now);
        if (shortage) {
            CanonicalOutbox.append(jdbc, "FulfillmentShortage.v1", "Fulfillment", request.fulfillmentId(),
                    request.tenantId(), request.workspaceId(), now, request.idempotencyKey(), null, "1.0",
                    request.idempotencyKey(), Map.of("fulfillmentId", request.fulfillmentId(), "pickingResultId", resultId));
        }
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PICK_CONFIRM",
                request.idempotencyKey(), request.requestHash(), request.fulfillmentId(), now);
        return load(request.tenantId(), request.workspaceId(), request.fulfillmentId());
    }

    static boolean isMixedPickingMode(Iterable<PickedLine> lines) {
        boolean physical = false;
        boolean legacy = false;
        for (PickedLine line : lines) {
            boolean hasPhysicalReference = hasPhysicalPickingReference(line);
            physical |= hasPhysicalReference;
            legacy |= !hasPhysicalReference;
        }
        return physical && legacy;
    }

    static boolean containsDuplicateLegacyLines(Map<UUID, List<PickedLine>> requested) {
        return requested.values().stream().anyMatch(lines -> lines.size() > 1
                && lines.stream().anyMatch(line -> !hasPhysicalPickingReference(line)));
    }

    private static boolean hasPhysicalPickingBinding(PickingRequest request) {
        return request.allocationVersion() != null || (request.lines() != null && request.lines().stream()
                .anyMatch(line -> line != null && hasPhysicalPickingReference(line)));
    }

    private static boolean hasPhysicalPickingReference(PickedLine line) {
        return line.physicalAllocationLineId() != null || line.lotId() != null || line.warehouseId() != null;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ShortageResolutionResult resolveShortage(ShortageResolutionRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        if (request.lines() == null || request.lines().isEmpty() || request.reason() == null || request.reason().isBlank()) {
            throw error("FULFILLMENT_SHORTAGE_RESOLUTION_INVALID");
        }
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "SHORTAGE_RESOLVE", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                "SHORTAGE_RESOLVE", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return new ShortageResolutionResult(load(request.tenantId(), request.workspaceId(), request.fulfillmentId()), prior.resourceId());
        }

        FulfillmentRow current = lockFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        if (!"SHORTAGE".equals(current.status())) throw error("FULFILLMENT_SHORTAGE_NOT_OPEN");
        Map<UUID, ShortageLine> requested = new HashMap<>();
        for (ShortageLine line : request.lines()) {
            if (line == null || line.fulfillmentLineId() == null || line.skuId() == null || line.quantity() == null
                    || line.quantity().signum() <= 0 || line.unit() == null || line.unit().isBlank()
                    || requested.put(line.fulfillmentLineId(), line) != null) {
                throw error("FULFILLMENT_SHORTAGE_RESOLUTION_INVALID");
            }
        }
        List<FulfillmentLineRow> currentLines = lockLines(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        List<FulfillmentLineRow> openShortages = currentLines.stream()
                .filter(line -> line.allocatedQuantity().subtract(line.pickedQuantity()).signum() > 0
                        && line.unfulfilledQuantity().signum() == 0)
                .toList();
        if (requested.size() != openShortages.size()) throw error("FULFILLMENT_SHORTAGE_RESOLUTION_INCOMPLETE");

        UUID resolutionId = UUID.randomUUID();
        Instant now = Objects.requireNonNull(request.now(), "now");
        for (FulfillmentLineRow line : openShortages) {
            ShortageLine shortage = requested.get(line.id());
            BigDecimal quantity = line.allocatedQuantity().subtract(line.pickedQuantity());
            if (shortage == null || !line.skuId().equals(shortage.skuId())
                    || !line.unit().equalsIgnoreCase(shortage.unit()) || shortage.quantity().compareTo(quantity) != 0) {
                throw error("FULFILLMENT_SHORTAGE_RESOLUTION_INVALID");
            }
            DiscrepancyRow discrepancy = jdbc.query(
                    "select d.id,d.quantity from logistics.picking_discrepancy d where d.tenant_id=? and d.workspace_id=? and d.fulfillment_id=? and d.fulfillment_line_id=? and d.kind='SHORTAGE' and not exists (select 1 from logistics.picking_discrepancy_resolution r where r.tenant_id=d.tenant_id and r.workspace_id=d.workspace_id and r.discrepancy_id=d.id) order by d.created_at desc,d.id desc limit 1",
                    (rs, row) -> new DiscrepancyRow(rs.getObject("id", UUID.class), rs.getBigDecimal("quantity")),
                    request.tenantId(), request.workspaceId(), request.fulfillmentId(), line.id())
                    .stream().findFirst().orElseThrow(() -> error("FULFILLMENT_SHORTAGE_EVIDENCE_MISSING"));
            if (discrepancy.quantity().compareTo(quantity) != 0) throw error("FULFILLMENT_SHORTAGE_EVIDENCE_INVALID");
            jdbc.update("insert into logistics.picking_discrepancy_resolution(id,tenant_id,workspace_id,discrepancy_id,resolution_id,fulfillment_id,fulfillment_line_id,resolution_type,quantity,reason,actor_membership_id,idempotency_key,request_hash,resolved_at,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), discrepancy.id(), resolutionId,
                    request.fulfillmentId(), line.id(), "FINAL_UNFULFILLED", quantity, bounded(request.reason()),
                    request.actorMembershipId(), request.idempotencyKey(), request.requestHash(), Timestamp.from(now), Timestamp.from(now));
            if (jdbc.update("update logistics.fulfillment_line set unfulfilled_quantity=unfulfilled_quantity+? where tenant_id=? and workspace_id=? and id=? and unfulfilled_quantity=0 and allocated_quantity-picked_quantity=?",
                    quantity, request.tenantId(), request.workspaceId(), line.id(), quantity) != 1) {
                throw error("CONCURRENCY_CONFLICT");
            }
        }
        boolean commerciallyResolved = Boolean.TRUE.equals(jdbc.queryForObject(
                "select not exists (select 1 from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? and remaining_quantity>0)",
                Boolean.class, request.tenantId(), request.workspaceId(), request.fulfillmentId()));
        String nextStatus = commerciallyResolved ? "COMPLETED" : "PICKED";
        String update = commerciallyResolved
                ? "update logistics.fulfillment set status='COMPLETED',completed_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='SHORTAGE' and version=?"
                : "update logistics.fulfillment set status='PICKED',updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='SHORTAGE' and version=?";
        Object[] updateArguments = commerciallyResolved
                ? new Object[]{Timestamp.from(now), Timestamp.from(now), request.tenantId(), request.workspaceId(), request.fulfillmentId(), request.expectedVersion()}
                : new Object[]{Timestamp.from(now), request.tenantId(), request.workspaceId(), request.fulfillmentId(), request.expectedVersion()};
        if (jdbc.update(update, updateArguments) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        insertEvent(request.tenantId(), request.workspaceId(), request.fulfillmentId(), current.status(), nextStatus,
                "SHORTAGE_RESOLVED", request.actorMembershipId(), bounded(request.reason()), now);
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "SHORTAGE_RESOLVE",
                request.idempotencyKey(), request.requestHash(), resolutionId, now);
        return new ShortageResolutionResult(load(request.tenantId(), request.workspaceId(), request.fulfillmentId()), resolutionId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FulfillmentView handOver(HandOverRequest request) {
        validateScope(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "HAND_OVER", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "HAND_OVER", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        FulfillmentRow current = lockFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        requireTransition(current.status(), "HANDED_OVER");
        Instant now = Objects.requireNonNull(request.now(), "now");
        if (jdbc.update("update logistics.fulfillment_line set dispatched_quantity=staged_quantity where tenant_id=? and workspace_id=? and fulfillment_id=? and staged_quantity>0",
                request.tenantId(), request.workspaceId(), request.fulfillmentId()) == 0) throw error("FULFILLMENT_NOT_STAGED");
        if (jdbc.update("update logistics.fulfillment set status='HANDED_OVER',dispatched_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                Timestamp.from(now), Timestamp.from(now), request.tenantId(), request.workspaceId(), request.fulfillmentId(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("insert into logistics.delivery(id,tenant_id,workspace_id,fulfillment_id,status,destination_snapshot,dispatched_at,created_at,updated_at,version) values (?,?,?,?, 'DISPATCHED',?,?,?, ?,0)",
                deliveryId, request.tenantId(), request.workspaceId(), request.fulfillmentId(), current.destinationSnapshot(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        insertEvent(request.tenantId(), request.workspaceId(), request.fulfillmentId(), current.status(), "HANDED_OVER",
                "HAND_OVER", request.actorMembershipId(), "Fulfillment handed over to delivery", now);
        insertIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "HAND_OVER",
                request.idempotencyKey(), request.requestHash(), request.fulfillmentId(), now);
        return load(request.tenantId(), request.workspaceId(), request.fulfillmentId());
    }

    private FulfillmentView load(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        FulfillmentRow header = jdbc.query("select f.id,f.sales_order_id,f.physical_allocation_id,f.status,f.destination_snapshot,f.version,f.created_at,f.updated_at,d.id delivery_id,d.status delivery_status,d.version delivery_version from logistics.fulfillment f left join logistics.delivery d on d.tenant_id=f.tenant_id and d.workspace_id=f.workspace_id and d.fulfillment_id=f.id where f.tenant_id=? and f.workspace_id=? and f.id=?",
                (rs, row) -> new FulfillmentRow(rs.getObject("id", UUID.class), rs.getObject("sales_order_id", UUID.class),
                        rs.getObject("physical_allocation_id", UUID.class), rs.getString("status"), rs.getString("destination_snapshot"),
                        rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"),
                        rs.getObject("delivery_id", UUID.class), rs.getString("delivery_status"),
                        number(rs, "delivery_version")), tenantId, workspaceId, fulfillmentId)
                .stream().findFirst().orElseThrow(() -> error("FULFILLMENT_NOT_FOUND"));
        List<LineView> lines = jdbc.query("select id,sku_id,catalog_item_id,ordered_quantity,backed_quantity,allocated_quantity,picked_quantity,packed_quantity,staged_quantity,dispatched_quantity,delivered_quantity,rejected_quantity,cancelled_quantity,unfulfilled_quantity,remaining_quantity,unit from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? order by id",
                (rs, row) -> new LineView(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class), rs.getString("catalog_item_id"),
                        rs.getBigDecimal("ordered_quantity"), rs.getBigDecimal("backed_quantity"), rs.getBigDecimal("allocated_quantity"),
                        rs.getBigDecimal("picked_quantity"), rs.getBigDecimal("packed_quantity"), rs.getBigDecimal("staged_quantity"),
                        rs.getBigDecimal("dispatched_quantity"), rs.getBigDecimal("delivered_quantity"), rs.getBigDecimal("rejected_quantity"),
                        rs.getBigDecimal("cancelled_quantity"), rs.getBigDecimal("unfulfilled_quantity"),
                        rs.getBigDecimal("remaining_quantity"), rs.getString("unit")),
                tenantId, workspaceId, fulfillmentId);
        return new FulfillmentView(header.id(), header.salesOrderId(), header.physicalAllocationId(), header.status(),
                header.destinationSnapshot(), header.version(), header.createdAt(), header.updatedAt(), header.deliveryId(),
                header.deliveryStatus(), header.deliveryVersion(), lines);
    }

    private FulfillmentRow lockFulfillment(UUID tenantId, UUID workspaceId, UUID id) {
        return jdbc.query("select id,sales_order_id,physical_allocation_id,status,destination_snapshot,version,created_at,updated_at from logistics.fulfillment where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new FulfillmentRow(rs.getObject("id", UUID.class), rs.getObject("sales_order_id", UUID.class),
                        rs.getObject("physical_allocation_id", UUID.class), rs.getString("status"), rs.getString("destination_snapshot"),
                        rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at")), tenantId, workspaceId, id)
                .stream().findFirst().orElseThrow(() -> error("FULFILLMENT_NOT_FOUND"));
    }

    private List<FulfillmentLineRow> lockLines(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        return jdbc.query("select id,sku_id,allocated_quantity,picked_quantity,unfulfilled_quantity,unit from logistics.fulfillment_line where tenant_id=? and workspace_id=? and fulfillment_id=? order by id for update",
                (rs, row) -> new FulfillmentLineRow(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("allocated_quantity"), rs.getBigDecimal("picked_quantity"),
                        rs.getBigDecimal("unfulfilled_quantity"), rs.getString("unit")), tenantId, workspaceId, fulfillmentId);
    }

    private void insertEvent(UUID tenantId, UUID workspaceId, UUID fulfillmentId, String from, String to,
                             String eventType, UUID actor, String reason, Instant occurredAt) {
        jdbc.update("insert into logistics.fulfillment_event(id,tenant_id,workspace_id,fulfillment_id,event_type,from_status,to_status,actor_membership_id,reason,occurred_at) values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, fulfillmentId, eventType, from, to, actor, bounded(reason), Timestamp.from(occurredAt));
    }

    private static void requireTransition(String current, String target) {
        boolean allowed = switch (target) {
            case "PICKING" -> "ALLOCATED".equals(current) || "HOLD".equals(current);
            case "PACKED" -> "PICKED".equals(current);
            case "STAGED" -> "PACKED".equals(current);
            case "READY_FOR_DISPATCH" -> "STAGED".equals(current);
            case "HANDED_OVER" -> "READY_FOR_DISPATCH".equals(current);
            case "HOLD" -> !SetOfTerminal.contains(current);
            case "CANCELLED" -> !SetOfTerminal.contains(current) && !"HANDED_OVER".equals(current);
            default -> false;
        };
        if (!allowed) throw error("FULFILLMENT_TRANSITION_INVALID");
    }

    private IdempotencyRow idempotency(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key) {
        return jdbc.query("select request_hash,resource_id from logistics.fulfillment_command_idempotency where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=? for update",
                (rs, row) -> new IdempotencyRow(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)),
                tenantId, workspaceId, actor, operation, key).stream().findFirst().orElse(null);
    }

    private void insertIdempotency(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key,
                                   String hash, UUID resourceId, Instant now) {
        jdbc.update("insert into logistics.fulfillment_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                tenantId, workspaceId, actor, operation, key, hash, resourceId, Timestamp.from(now));
    }

    private void lockCommand(UUID tenantId, UUID workspaceId, UUID actor, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenantId + "|" + workspaceId + "|fulfillment|" + actor + "|" + operation + "|" + key);
    }

    private static void validateScope(UUID tenantId, UUID workspaceId, UUID actor, String key) {
        if (tenantId == null || workspaceId == null || actor == null || key == null || key.isBlank()) throw error("INVALID_REQUEST");
    }

    private static void validateLine(CreateLine line) {
        if (line == null || line.id() == null || line.skuId() == null || line.catalogItemId() == null || line.catalogItemId().isBlank()
                || line.orderedQuantity() == null || line.orderedQuantity().signum() <= 0 || line.backedQuantity() == null
                || line.allocatedQuantity() == null || line.backedQuantity().compareTo(line.orderedQuantity()) > 0
                || line.allocatedQuantity().compareTo(line.backedQuantity()) > 0 || line.unit() == null || line.unit().isBlank()) {
            throw error("FULFILLMENT_LINE_INVALID");
        }
    }

    private static void ensureHash(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000);
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

    private static final java.util.Set<String> SetOfTerminal = java.util.Set.of("COMPLETED", "CANCELLED");

    private record IdempotencyRow(String requestHash, UUID resourceId) { }
    private record FulfillmentLineRow(UUID id, UUID skuId, BigDecimal allocatedQuantity,
                                      BigDecimal pickedQuantity, BigDecimal unfulfilledQuantity, String unit) { }
    private record DiscrepancyRow(UUID id, BigDecimal quantity) { }
    private record FulfillmentRow(UUID id, UUID salesOrderId, UUID physicalAllocationId, String status,
                                  String destinationSnapshot, long version, Instant createdAt, Instant updatedAt,
                                  UUID deliveryId, String deliveryStatus, long deliveryVersion) {
        private FulfillmentRow(UUID id, UUID salesOrderId, UUID physicalAllocationId, String status,
                               String destinationSnapshot, long version, Instant createdAt, Instant updatedAt) {
            this(id, salesOrderId, physicalAllocationId, status, destinationSnapshot, version, createdAt, updatedAt,
                    null, null, -1L);
        }
    }
}
