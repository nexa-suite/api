package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort;
import com.nexa.api.fulfillmentdelivery.domain.model.delivery.DeliveryAttemptOutcome;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingQuery;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentCommands;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * BC-06 application boundary. It coordinates BC-04, BC-05, BC-07 and BC-09
 * only through their public contracts; lifecycle state and SQL remain owned by
 * their respective contexts.
 */
@Service
@Profile("!test")
public final class FulfillmentLifecycleService {
    private final SalesOrderFulfillmentQuery salesOrders;
    private final SalesOrderFulfillmentCommands salesOrderCommands;
    private final InventoryBackingQuery inventoryBackings;
    private final PhysicalAllocationCommands physicalAllocations;
    private final FulfillmentPersistencePort fulfillments;
    private final DeliveryPersistencePort deliveries;
    private final FinancialAdjustmentCommands financialAdjustments;
    private final BusinessEvidenceQuery businessEvidence;
    private final BusinessTraceabilityCommands traceability;
    private final Clock clock;

    public FulfillmentLifecycleService(SalesOrderFulfillmentQuery salesOrders,
                                       SalesOrderFulfillmentCommands salesOrderCommands,
                                       InventoryBackingQuery inventoryBackings,
                                       PhysicalAllocationCommands physicalAllocations,
                                       FulfillmentPersistencePort fulfillments,
                                       DeliveryPersistencePort deliveries,
                                       FinancialAdjustmentCommands financialAdjustments,
                                       BusinessEvidenceQuery businessEvidence,
                                       BusinessTraceabilityCommands traceability,
                                       Clock clock) {
        this.salesOrders = Objects.requireNonNull(salesOrders, "Sales Order query is required");
        this.salesOrderCommands = Objects.requireNonNull(salesOrderCommands, "Sales Order commands are required");
        this.inventoryBackings = Objects.requireNonNull(inventoryBackings, "Inventory backing query is required");
        this.physicalAllocations = Objects.requireNonNull(physicalAllocations, "Physical allocation commands are required");
        this.fulfillments = Objects.requireNonNull(fulfillments, "Fulfillment persistence is required");
        this.deliveries = Objects.requireNonNull(deliveries, "Delivery persistence is required");
        this.financialAdjustments = Objects.requireNonNull(financialAdjustments, "Financial adjustment commands are required");
        this.businessEvidence = Objects.requireNonNull(businessEvidence, "Business evidence query is required");
        this.traceability = Objects.requireNonNull(traceability, "Business traceability is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public FulfillmentModels.FulfillmentView start(CurrentAccessContext context, UUID salesOrderId,
                                                    long expectedOrderVersion, String idempotencyKey) {
        fulfillmentWrite(context);
        requireKey(idempotencyKey);
        requireVersion(expectedOrderVersion);
        SalesOrderFulfillmentQuery.Snapshot order = salesOrders.getForUpdate(tenant(context), workspace(context), salesOrderId);
        FulfillmentModels.FulfillmentView existing = fulfillments.findBySalesOrder(tenant(context), workspace(context), salesOrderId);
        if (existing != null) return existing;
        if (order.version() != expectedOrderVersion) throw conflict("SALES_ORDER_CONCURRENCY_CONFLICT");
        if (!"CONFIRMED".equals(order.status())) throw invalid("SALES_ORDER_NOT_CONFIRMED");
        if (order.commercialCommitmentId() == null) throw invalid("COMMERCIAL_COMMITMENT_REQUIRED");
        if (order.lines().isEmpty()) throw invalid("SALES_ORDER_LINES_REQUIRED");

        InventoryBackingQuery.Snapshot backing = inventoryBackings.findByCommitment(
                        tenant(context), workspace(context), order.commercialCommitmentId())
                .orElseThrow(() -> invalid("INVENTORY_BACKING_NOT_FOUND"));
        if (!"BACKED".equals(backing.status())) throw invalid("INVENTORY_BACKING_NOT_READY");
        Map<LineKey, BigDecimal> available = available(backing.positions());
        Set<LineKey> seen = new HashSet<>();
        List<PhysicalAllocationCommands.RequestedLine> requested = new ArrayList<>();
        List<FulfillmentPersistencePort.CreateLine> fulfillmentLines = new ArrayList<>();
        for (SalesOrderFulfillmentQuery.Line line : order.lines()) {
            validateOrderLine(line);
            LineKey key = new LineKey(line.skuId(), line.catalogItemId(), line.unit());
            if (!seen.add(key)) throw invalid("SALES_ORDER_LINE_DUPLICATED");
            if (available.getOrDefault(key, BigDecimal.ZERO).compareTo(line.quantity()) < 0) {
                throw invalid("INVENTORY_BACKING_INSUFFICIENT");
            }
            requested.add(new PhysicalAllocationCommands.RequestedLine(
                    line.skuId(), line.catalogItemId(), line.quantity(), line.unit()));
        }
        UUID fulfillmentId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        String requestHash = hash("physical-allocation-v1|" + orderCanonical(order));
        PhysicalAllocationCommands.AllocationResult allocation = physicalAllocations.allocate(
                new PhysicalAllocationCommands.AllocationRequest(
                        tenant(context), workspace(context), order.id(), order.commercialCommitmentId(),
                        backing.id(), fulfillmentId, allocationId, actor(context),
                        operationKey("physical-allocation-", idempotencyKey), requestHash, requested, now()));
        if (!allocationId.equals(allocation.allocationId()) || !"ALLOCATED".equals(allocation.status())) {
            throw invalid("PHYSICAL_ALLOCATION_NOT_ALLOCATED");
        }
        for (SalesOrderFulfillmentQuery.Line line : order.lines()) {
            fulfillmentLines.add(new FulfillmentPersistencePort.CreateLine(
                    UUID.randomUUID(), line.skuId(), line.catalogItemId(), line.quantity(), line.quantity(), line.quantity(), line.unit()));
        }
        FulfillmentModels.FulfillmentView result = fulfillments.createAllocated(
                new FulfillmentPersistencePort.CreateRequest(
                        tenant(context), workspace(context), fulfillmentId, order.id(), allocationId,
                        order.destinationSnapshot(), actor(context), idempotencyKey, hash("fulfillment-start-v1|" + orderCanonical(order)),
                        now(), fulfillmentLines));
        salesOrderCommands.markInFulfillment(tenant(context), workspace(context), order.id(), actor(context), now());
        trace(context, "FULFILLMENT_STARTED", "Fulfillment", result.id(), idempotencyKey,
                Map.of("salesOrderId", order.id(), "physicalAllocationId", allocationId));
        return result;
    }

    @Transactional(readOnly = true)
    public FulfillmentModels.FulfillmentView get(CurrentAccessContext context, UUID fulfillmentId) {
        context.requirePermission(PermissionKey.FULFILLMENT_READ);
        return fulfillments.find(tenant(context), workspace(context), fulfillmentId);
    }

    @Transactional
    public FulfillmentModels.FulfillmentView startPicking(CurrentAccessContext context, UUID fulfillmentId,
                                                          long expectedVersion, String idempotencyKey) {
        fulfillmentWrite(context);
        FulfillmentModels.FulfillmentView result = transition(context, fulfillmentId, expectedVersion, idempotencyKey,
                "PICKING_START", "PICKING", "Picking started");
        trace(context, "FULFILLMENT_PICKING_STARTED", "Fulfillment", fulfillmentId, idempotencyKey, Map.of());
        return result;
    }

    @Transactional
    public FulfillmentModels.FulfillmentView confirmPicking(CurrentAccessContext context, UUID fulfillmentId,
                                                             long expectedVersion, String idempotencyKey,
                                                             PickingCommand command) {
        fulfillmentWrite(context);
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        if (command == null || command.lines() == null || command.lines().isEmpty()) throw invalid("FULFILLMENT_PICKING_LINES_REQUIRED");
        List<FulfillmentPersistencePort.PickedLine> lines = command.lines().stream()
                .map(line -> new FulfillmentPersistencePort.PickedLine(line.fulfillmentLineId(), line.skuId(), line.quantity(), line.unit()))
                .toList();
        FulfillmentModels.FulfillmentView result = fulfillments.confirmPicking(
                new FulfillmentPersistencePort.PickingRequest(
                        tenant(context), workspace(context), fulfillmentId, expectedVersion, actor(context),
                        command.pickerIdentityId() == null ? context.userId().value() : command.pickerIdentityId(),
                        idempotencyKey, hash("picking-confirm-v1|" + fulfillmentId + "|" + expectedVersion + "|" + pickingCanonical(command)),
                        command.startedAt(), command.completedAt(), bounded(command.notes()), lines));
        trace(context, "FULFILLMENT_PICKING_CONFIRMED", "Fulfillment", fulfillmentId, idempotencyKey,
                Map.of("status", result.status(), "lineCount", lines.size()));
        if ("SHORTAGE".equals(result.status())) {
            trace(context, "FULFILLMENT_SHORTAGE_RECORDED", "Fulfillment", fulfillmentId,
                    operationKey("shortage-trace-", idempotencyKey), Map.of("lineCount", lines.size()));
        }
        return result;
    }

    @Transactional
    public FulfillmentModels.FulfillmentView resolveShortage(CurrentAccessContext context, UUID fulfillmentId,
                                                              long expectedVersion, String idempotencyKey,
                                                              ShortageResolutionCommand command) {
        fulfillmentWrite(context);
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        if (command == null || command.lines() == null || command.lines().isEmpty()
                || command.reason() == null || command.reason().isBlank()) {
            throw invalid("FULFILLMENT_SHORTAGE_RESOLUTION_INVALID");
        }
        FulfillmentModels.FulfillmentView current = fulfillments.find(tenant(context), workspace(context), fulfillmentId);
        if (!"SHORTAGE".equals(current.status()) || current.version() != expectedVersion) {
            throw conflict("FULFILLMENT_SHORTAGE_NOT_OPEN");
        }
        Map<UUID, FulfillmentModels.LineView> currentLines = new HashMap<>();
        current.lines().forEach(line -> currentLines.put(line.id(), line));
        SalesOrderFulfillmentQuery.Snapshot order = salesOrders.get(tenant(context), workspace(context), current.salesOrderId());
        Map<LineKey, SalesOrderFulfillmentQuery.Line> pricedLines = new HashMap<>();
        order.lines().forEach(line -> pricedLines.put(new LineKey(line.skuId(), line.catalogItemId(), line.unit()), line));
        Set<UUID> seen = new HashSet<>();
        List<FulfillmentPersistencePort.ShortageLine> resolutionLines = new ArrayList<>();
        List<PhysicalAllocationCommands.UnpickedLine> unpickedLines = new ArrayList<>();
        BigDecimal adjustmentAmount = BigDecimal.ZERO;
        String adjustmentCurrency = null;
        for (ShortageLineCommand requested : command.lines()) {
            if (requested == null || requested.fulfillmentLineId() == null || !seen.add(requested.fulfillmentLineId())
                    || requested.quantity() == null || requested.quantity().signum() <= 0
                    || requested.skuId() == null || requested.unit() == null || requested.unit().isBlank()) {
                throw invalid("FULFILLMENT_SHORTAGE_RESOLUTION_INVALID");
            }
            FulfillmentModels.LineView line = currentLines.get(requested.fulfillmentLineId());
            if (line == null || line.unfulfilledQuantity().signum() != 0
                    || !line.skuId().equals(requested.skuId()) || !line.unit().equalsIgnoreCase(requested.unit())) {
                throw invalid("FULFILLMENT_SHORTAGE_RESOLUTION_INVALID");
            }
            BigDecimal shortage = line.allocatedQuantity().subtract(line.pickedQuantity());
            if (shortage.signum() <= 0 || requested.quantity().compareTo(shortage) != 0) {
                throw invalid("FULFILLMENT_SHORTAGE_RESOLUTION_INVALID");
            }
            SalesOrderFulfillmentQuery.Line priced = pricedLines.get(new LineKey(line.skuId(), line.catalogItemId(), line.unit()));
            if (priced == null || priced.unitPriceAmount() == null || priced.unitPriceAmount().signum() < 0) {
                throw invalid("FINAL_QUANTITY_PRICE_REQUIRED");
            }
            String currency = priced.currency() == null ? order.currency() : priced.currency();
            if (currency == null || currency.isBlank()) throw invalid("FINAL_QUANTITY_PRICE_REQUIRED");
            String normalizedCurrency = currency.trim().toUpperCase(java.util.Locale.ROOT);
            if (adjustmentCurrency == null) adjustmentCurrency = normalizedCurrency;
            if (!adjustmentCurrency.equals(normalizedCurrency)) throw invalid("FINAL_QUANTITY_CURRENCY_MISMATCH");
            adjustmentAmount = adjustmentAmount.add(priced.unitPriceAmount().multiply(shortage));
            resolutionLines.add(new FulfillmentPersistencePort.ShortageLine(
                    line.id(), line.skuId(), shortage, line.unit()));
            unpickedLines.add(new PhysicalAllocationCommands.UnpickedLine(
                    line.id(), line.skuId(), line.catalogItemId(), shortage, line.unit()));
        }
        if (seen.size() != current.lines().stream()
                .filter(line -> line.allocatedQuantity().subtract(line.pickedQuantity()).signum() > 0
                        && line.unfulfilledQuantity().signum() == 0).count()) {
            throw invalid("FULFILLMENT_SHORTAGE_RESOLUTION_INCOMPLETE");
        }
        String canonical = shortageResolutionCanonical(command);
        PhysicalAllocationCommands.AllocationResult allocation = physicalAllocations.getByFulfillment(
                tenant(context), workspace(context), fulfillmentId);
        physicalAllocations.reconcileUnpicked(new PhysicalAllocationCommands.ReconcileUnpickedRequest(
                tenant(context), workspace(context), fulfillmentId, actor(context),
                operationKey("physical-reconcile-", idempotencyKey), hash("physical-reconcile-v1|" + fulfillmentId + "|" + canonical),
                allocation.version(), bounded(command.reason()), unpickedLines, now()));
        FulfillmentPersistencePort.ShortageResolutionResult resolved = fulfillments.resolveShortage(
                new FulfillmentPersistencePort.ShortageResolutionRequest(
                        tenant(context), workspace(context), fulfillmentId, expectedVersion, actor(context), idempotencyKey,
                        hash("shortage-resolution-v1|" + fulfillmentId + "|" + expectedVersion + "|" + canonical),
                        bounded(command.reason()), now(), resolutionLines));
        if (adjustmentAmount.signum() > 0) {
            financialAdjustments.postFinalQuantityAdjustment(new FinancialAdjustmentCommands.Request(
                    tenant(context), workspace(context), actor(context), context.userId().value(),
                    null, current.salesOrderId(), null, resolved.resolutionId(),
                    "DECREASE", "DECREASE", adjustmentAmount, adjustmentCurrency,
                    bounded(command.reason()), "FINAL_PICKING_SHORTAGE",
                    operationKey("final-picking-adjustment-", idempotencyKey),
                    hash("final-picking-adjustment-v1|" + resolved.resolutionId() + "|" + adjustmentAmount + "|" + adjustmentCurrency),
                    "CUSTOMER_CREDIT", null, now()));
        }
        if (resolved.fulfillment().lines().stream().allMatch(line -> line.remainingQuantity().signum() == 0)) {
            salesOrderCommands.markCompleted(tenant(context), workspace(context), current.salesOrderId(), actor(context), now(),
                    "Final shortage resolved; no commercial quantity remains", BigDecimal.ZERO);
        }
        trace(context, "FULFILLMENT_SHORTAGE_RESOLVED", "Fulfillment", fulfillmentId, idempotencyKey,
                Map.of("resolutionId", resolved.resolutionId(), "adjustmentAmount", adjustmentAmount));
        return resolved.fulfillment();
    }

    @Transactional
    public FulfillmentModels.FulfillmentView pack(CurrentAccessContext context, UUID fulfillmentId,
                                                   long expectedVersion, String idempotencyKey) {
        return transitionWithTrace(context, fulfillmentId, expectedVersion, idempotencyKey,
                "PACK", "PACKED", "Fulfillment packed", "FULFILLMENT_PACKED");
    }

    @Transactional
    public FulfillmentModels.FulfillmentView stage(CurrentAccessContext context, UUID fulfillmentId,
                                                    long expectedVersion, String idempotencyKey) {
        return transitionWithTrace(context, fulfillmentId, expectedVersion, idempotencyKey,
                "STAGE", "STAGED", "Fulfillment staged", "FULFILLMENT_STAGED");
    }

    @Transactional
    public FulfillmentModels.FulfillmentView readyForDispatch(CurrentAccessContext context, UUID fulfillmentId,
                                                               long expectedVersion, String idempotencyKey) {
        return transitionWithTrace(context, fulfillmentId, expectedVersion, idempotencyKey,
                "READY_FOR_DISPATCH", "READY_FOR_DISPATCH", "Fulfillment ready for dispatch", "FULFILLMENT_READY_FOR_DISPATCH");
    }

    @Transactional
    public FulfillmentModels.FulfillmentView dispatch(CurrentAccessContext context, UUID fulfillmentId,
                                                       long expectedVersion, String idempotencyKey) {
        fulfillmentWrite(context);
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        FulfillmentModels.FulfillmentView current = fulfillments.find(tenant(context), workspace(context), fulfillmentId);
        if ("HANDED_OVER".equals(current.status())) return current;
        if (current.version() != expectedVersion) throw conflict("FULFILLMENT_CONCURRENCY_CONFLICT");
        PhysicalAllocationCommands.AllocationResult allocation = physicalAllocations.getByFulfillment(
                tenant(context), workspace(context), fulfillmentId);
        physicalAllocations.consumeForDispatch(new PhysicalAllocationCommands.ConsumeRequest(
                tenant(context), workspace(context), fulfillmentId, actor(context),
                operationKey("physical-consume-", idempotencyKey),
                hash("physical-consume-v1|" + fulfillmentId + "|" + expectedVersion + "|" + allocation.version()),
                allocation.version(), now()));
        FulfillmentModels.FulfillmentView result = fulfillments.handOver(
                new FulfillmentPersistencePort.HandOverRequest(
                        tenant(context), workspace(context), fulfillmentId, expectedVersion, actor(context),
                        idempotencyKey, hash("handover-v1|" + fulfillmentId + "|" + expectedVersion), now()));
        trace(context, "FULFILLMENT_HANDED_OVER", "Fulfillment", fulfillmentId, idempotencyKey,
                Map.of("deliveryId", Objects.requireNonNull(result.deliveryId(), "Delivery was not created")));
        return result;
    }

    @Transactional(readOnly = true)
    public FulfillmentModels.DeliveryView getDelivery(CurrentAccessContext context, UUID deliveryId) {
        context.requirePermission(Permission.LOGISTICS_READ);
        return deliveries.find(tenant(context), workspace(context), deliveryId);
    }

    @Transactional
    public FulfillmentModels.DeliveryView startDelivery(CurrentAccessContext context, UUID deliveryId,
                                                        long expectedVersion, String idempotencyKey) {
        logisticsWrite(context);
        FulfillmentModels.DeliveryView result = deliveryTransition(context, deliveryId, expectedVersion, idempotencyKey,
                "TRANSIT_START", "IN_TRANSIT", "Delivery entered transit");
        trace(context, "DELIVERY_TRANSIT_STARTED", "Delivery", deliveryId, idempotencyKey, Map.of());
        return result;
    }

    @Transactional
    public FulfillmentModels.DeliveryOutcomeResult recordAttempt(CurrentAccessContext context, UUID deliveryId,
                                                                  long expectedVersion, String idempotencyKey,
                                                                  AttemptCommand command) {
        logisticsWrite(context);
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        if (command == null || command.outcome() == null || command.outcome() == DeliveryAttemptOutcome.PENDING) {
            throw invalid("DELIVERY_OUTCOME_REQUIRED");
        }
        FulfillmentModels.DeliveryView delivery = deliveries.find(tenant(context), workspace(context), deliveryId);
        if (delivery.fulfillmentId() == null || delivery.salesOrderId() == null) throw invalid("DELIVERY_NOT_FULFILLMENT_BACKED");
        FulfillmentModels.FulfillmentView fulfillment = fulfillments.find(tenant(context), workspace(context), delivery.fulfillmentId());
        SalesOrderFulfillmentQuery.Snapshot order = salesOrders.get(tenant(context), workspace(context), delivery.salesOrderId());
        Map<UUID, FulfillmentModels.LineView> fulfillmentLines = new HashMap<>();
        fulfillment.lines().forEach(line -> fulfillmentLines.put(line.id(), line));
        Map<LineKey, SalesOrderFulfillmentQuery.Line> orderLines = new HashMap<>();
        order.lines().forEach(line -> orderLines.put(new LineKey(line.skuId(), line.catalogItemId(), line.unit()), line));
        List<DeliveryPersistencePort.AttemptLine> lines = new ArrayList<>();
        for (AttemptLineCommand line : command.lines()) {
            FulfillmentModels.LineView fulfillmentLine = fulfillmentLines.get(line.fulfillmentLineId());
            if (fulfillmentLine == null) throw invalid("DELIVERY_OUTCOME_LINE_INVALID");
            SalesOrderFulfillmentQuery.Line priced = orderLines.get(new LineKey(
                    fulfillmentLine.skuId(), fulfillmentLine.catalogItemId(), fulfillmentLine.unit()));
            if (priced == null) throw invalid("DELIVERY_OUTCOME_PRICE_NOT_FOUND");
            String currency = priced.currency() == null ? order.currency() : priced.currency();
            lines.add(new DeliveryPersistencePort.AttemptLine(
                    line.fulfillmentLineId(), line.skuId(), line.attemptedQuantity(), line.deliveredQuantity(),
                    line.rejectedQuantity(), line.cancelledQuantity(), priced.unitPriceAmount(), currency, line.unit()));
        }
        FulfillmentModels.DeliveryOutcomeResult result = deliveries.recordAttempt(
                new DeliveryPersistencePort.AttemptRequest(
                        tenant(context), workspace(context), deliveryId, order.clientAccountId(), expectedVersion, actor(context), idempotencyKey,
                        hash("delivery-attempt-v1|" + deliveryId + "|" + expectedVersion + "|" + attemptCanonical(command)),
                        command.outcome(), bounded(command.failureReason()), bounded(command.notes()), command.attemptedAt(), lines));
        if (result.finalAdjustmentAmount() != null && result.finalAdjustmentAmount().signum() > 0) {
            financialAdjustments.postFinalQuantityAdjustment(new FinancialAdjustmentCommands.Request(
                    tenant(context), workspace(context), actor(context), context.userId().value(),
                    result.receivableId(), result.salesOrderId(), deliveryId, result.attemptId(),
                    "DECREASE", "DECREASE", result.finalAdjustmentAmount(), result.adjustmentCurrency(),
                    "Final undelivered quantity adjustment", "FINAL_UNDELIVERED_QUANTITY",
                    operationKey("final-adjustment-", idempotencyKey),
                    hash("final-adjustment-v1|" + result.attemptId() + "|" + result.finalAdjustmentAmount() + "|" + result.adjustmentCurrency()),
                    "CUSTOMER_CREDIT", null, now()));
        }
        if (result.allCommercialQuantityResolved()) {
            salesOrderCommands.markCompleted(tenant(context), workspace(context), result.salesOrderId(), actor(context), now(),
                    "Delivery quantities commercially resolved", BigDecimal.ZERO);
        } else if (result.partial() && command.outcome() != DeliveryAttemptOutcome.FAILED) {
            salesOrderCommands.markPartiallyDelivered(tenant(context), workspace(context), result.salesOrderId(), actor(context), now(),
                    "Partial delivery recorded");
        }
        trace(context, "DELIVERY_OUTCOME_RECORDED", "Delivery", deliveryId, idempotencyKey,
                Map.of("attemptId", result.attemptId(), "outcome", command.outcome().name(),
                        "allCommercialQuantityResolved", result.allCommercialQuantityResolved()));
        return result;
    }

    @Transactional
    public FulfillmentModels.PodView capturePod(CurrentAccessContext context, UUID deliveryId,
                                                long expectedVersion, String idempotencyKey, PodCommand command) {
        logisticsWrite(context);
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        if (command == null || command.receiverName() == null || command.receiverName().isBlank()) throw invalid("POD_RECEIVER_REQUIRED");
        requireEvidence(context, command.photoEvidenceObjectId());
        requireEvidence(context, command.signatureEvidenceObjectId());
        FulfillmentModels.PodView result = deliveries.capturePod(new DeliveryPersistencePort.PodRequest(
                tenant(context), workspace(context), deliveryId, expectedVersion, actor(context), idempotencyKey,
                hash("pod-capture-v1|" + deliveryId + "|" + expectedVersion + "|" + podCanonical(command)),
                command.receiverName(), command.capturedAt(), bounded(command.notes()),
                command.photoEvidenceObjectId(), command.signatureEvidenceObjectId()));
        trace(context, "POD_CAPTURED", "ProofOfDelivery", result.id(), idempotencyKey,
                Map.of("deliveryId", deliveryId));
        return result;
    }

    @Transactional
    public FulfillmentModels.PodView sealPod(CurrentAccessContext context, UUID deliveryId,
                                              long expectedVersion, String idempotencyKey, Instant sealedAt) {
        logisticsWrite(context);
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        FulfillmentModels.PodView result = deliveries.sealPod(new DeliveryPersistencePort.PodSealRequest(
                tenant(context), workspace(context), deliveryId, expectedVersion, actor(context), idempotencyKey,
                hash("pod-seal-v1|" + deliveryId + "|" + expectedVersion + "|" + (sealedAt == null ? "<now>" : sealedAt)), sealedAt));
        trace(context, "POD_SEALED", "ProofOfDelivery", result.id(), idempotencyKey,
                Map.of("deliveryId", deliveryId));
        return result;
    }

    @Transactional
    public FulfillmentModels.TemperatureView recordTemperature(CurrentAccessContext context, UUID deliveryId,
                                                                long expectedVersion, String idempotencyKey,
                                                                TemperatureCommand command) {
        logisticsWrite(context);
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        if (command == null || command.temperatureCelsius() == null) throw invalid("TEMPERATURE_REQUIRED");
        FulfillmentModels.TemperatureView result = deliveries.recordTemperature(new DeliveryPersistencePort.TemperatureRequest(
                tenant(context), workspace(context), deliveryId, command.lotId(), actor(context), idempotencyKey,
                hash("temperature-v1|" + deliveryId + "|" + expectedVersion + "|" + temperatureCanonical(command)),
                command.temperatureCelsius(), command.unit(), bounded(command.source()), bounded(command.evidenceMetadata()),
                command.recordedAt(), expectedVersion));
        trace(context, "TEMPERATURE_EVIDENCE_RECORDED", "Delivery", deliveryId, idempotencyKey,
                Map.of("temperatureEvidenceId", result.id(), "status", result.status()));
        return result;
    }

    private FulfillmentModels.FulfillmentView transitionWithTrace(CurrentAccessContext context, UUID fulfillmentId,
                                                                    long expectedVersion, String idempotencyKey,
                                                                    String operation, String target, String reason,
                                                                    String traceEvent) {
        fulfillmentWrite(context);
        FulfillmentModels.FulfillmentView result = transition(context, fulfillmentId, expectedVersion, idempotencyKey,
                operation, target, reason);
        trace(context, traceEvent, "Fulfillment", fulfillmentId, idempotencyKey, Map.of());
        return result;
    }

    private FulfillmentModels.FulfillmentView transition(CurrentAccessContext context, UUID fulfillmentId,
                                                          long expectedVersion, String idempotencyKey,
                                                          String operation, String target, String reason) {
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        return fulfillments.transition(new FulfillmentPersistencePort.TransitionRequest(
                tenant(context), workspace(context), fulfillmentId, expectedVersion, actor(context), operation, target,
                idempotencyKey, hash("fulfillment-transition-v1|" + fulfillmentId + "|" + expectedVersion + "|" + target),
                reason, now()));
    }

    private FulfillmentModels.DeliveryView deliveryTransition(CurrentAccessContext context, UUID deliveryId,
                                                               long expectedVersion, String idempotencyKey,
                                                               String operation, String target, String reason) {
        requireKey(idempotencyKey);
        requireVersion(expectedVersion);
        return deliveries.transition(new DeliveryPersistencePort.TransitionRequest(
                tenant(context), workspace(context), deliveryId, expectedVersion, actor(context), operation, target,
                idempotencyKey, hash("delivery-transition-v1|" + deliveryId + "|" + expectedVersion + "|" + target),
                reason, now()));
    }

    private void requireEvidence(CurrentAccessContext context, UUID evidenceObjectId) {
        if (evidenceObjectId != null && !businessEvidence.isAvailable(tenant(context), workspace(context), evidenceObjectId)) {
            throw invalid("BUSINESS_EVIDENCE_NOT_AVAILABLE");
        }
    }

    private void trace(CurrentAccessContext context, String eventType, String subjectType, UUID subjectId,
                       String occurrenceKey, Map<String, Object> metadata) {
        traceability.record(new BusinessTraceabilityCommands.TraceRequest(
                tenant(context), workspace(context), actor(context), "FULFILLMENT_DELIVERY", eventType,
                subjectType, subjectId, occurrenceKey, operationKey("trace-", occurrenceKey), metadata, now()));
    }

    private static Map<LineKey, BigDecimal> available(List<InventoryBackingQuery.Position> positions) {
        Map<LineKey, BigDecimal> result = new HashMap<>();
        for (InventoryBackingQuery.Position position : positions == null ? List.<InventoryBackingQuery.Position>of() : positions) {
            if (position == null || position.skuId() == null || position.catalogItemId() == null || position.unit() == null
                    || position.warehouseId() == null || position.quantity() == null || position.quantity().signum() <= 0) {
                throw invalid("INVENTORY_BACKING_POSITION_INVALID");
            }
            LineKey key = new LineKey(position.skuId(), position.catalogItemId(), position.unit());
            result.merge(key, position.quantity(), BigDecimal::add);
        }
        return result;
    }

    private static void validateOrderLine(SalesOrderFulfillmentQuery.Line line) {
        if (line == null || line.id() == null || line.skuId() == null || line.catalogItemId() == null
                || line.catalogItemId().isBlank() || line.quantity() == null || line.quantity().signum() <= 0
                || line.unit() == null || line.unit().isBlank()) throw invalid("SALES_ORDER_LINE_INVALID");
    }

    private static String orderCanonical(SalesOrderFulfillmentQuery.Snapshot order) {
        return order.id() + "|" + order.version() + "|" + Objects.toString(order.destinationSnapshot(), "<null>") + "|"
                + order.lines().stream().sorted(Comparator.comparing(line -> line.id().toString()))
                .map(line -> line.id() + ":" + line.skuId() + ":" + line.catalogItemId() + ":" + line.quantity() + ":" + line.unit())
                .reduce((left, right) -> left + ";" + right).orElse("");
    }

    private static String pickingCanonical(PickingCommand command) {
        return command.lines().stream().sorted(Comparator.comparing(line -> line.fulfillmentLineId().toString()))
                .map(line -> line.fulfillmentLineId() + ":" + line.skuId() + ":" + line.quantity() + ":" + line.unit())
                .reduce((left, right) -> left + ";" + right).orElse("");
    }

    private static String attemptCanonical(AttemptCommand command) {
        return command.outcome() + "|" + Objects.toString(command.failureReason(), "<null>") + "|"
                + command.lines().stream().sorted(Comparator.comparing(line -> line.fulfillmentLineId().toString()))
                .map(line -> line.fulfillmentLineId() + ":" + line.skuId() + ":" + line.attemptedQuantity() + ":"
                        + line.deliveredQuantity() + ":" + line.rejectedQuantity() + ":" + line.cancelledQuantity() + ":" + line.unit())
                .reduce((left, right) -> left + ";" + right).orElse("");
    }

    private static String podCanonical(PodCommand command) {
        return command.receiverName() + "|" + Objects.toString(command.notes(), "<null>") + "|"
                + command.photoEvidenceObjectId() + "|" + command.signatureEvidenceObjectId();
    }

    private static String temperatureCanonical(TemperatureCommand command) {
        return command.lotId() + "|" + command.temperatureCelsius() + "|" + command.unit() + "|"
                + command.source() + "|" + command.recordedAt();
    }

    private static String shortageResolutionCanonical(ShortageResolutionCommand command) {
        return Objects.toString(command.reason(), "<null>") + "|" + command.lines().stream()
                .sorted(Comparator.comparing(line -> line.fulfillmentLineId().toString()))
                .map(line -> line.fulfillmentLineId() + ":" + line.skuId() + ":" + line.quantity() + ":" + line.unit())
                .reduce((left, right) -> left + ";" + right).orElse("");
    }

    private static String operationKey(String prefix, String input) {
        return prefix + hash(input).substring(0, 120);
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private Instant now() { return clock.instant(); }
    private static UUID tenant(CurrentAccessContext context) { return context.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext context) { return context.workspaceId().value(); }
    private static UUID actor(CurrentAccessContext context) { return context.membershipId().value(); }
    private static String bounded(String value) { if (value == null || value.isBlank()) return null; String v = value.trim(); return v.length() <= 2000 ? v : v.substring(0, 2000); }
    private static void requireKey(String key) { if (key == null || key.isBlank() || key.length() > 160) throw invalid("IDEMPOTENCY_KEY_REQUIRED"); }
    private static void requireVersion(long version) { if (version < 0) throw invalid("VERSION_INVALID"); }
    private static void fulfillmentWrite(CurrentAccessContext context) { context.requirePermission(PermissionKey.FULFILLMENT_MANAGE); }
    private static void logisticsWrite(CurrentAccessContext context) { context.requirePermission(Permission.LOGISTICS_WRITE); }
    private static FulfillmentOperationException invalid(String code) {
        return new FulfillmentOperationException(code, code != null && code.endsWith("_NOT_FOUND"));
    }
    private static FulfillmentOperationException conflict(String code) {
        return new FulfillmentOperationException(code, false);
    }

    private record LineKey(UUID skuId, String catalogItemId, String unit) { }

    public record PickingCommand(UUID pickerIdentityId, Instant startedAt, Instant completedAt,
                                 String notes, List<PickingLine> lines) {
        public PickingCommand {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    public record PickingLine(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit) { }

    public record ShortageResolutionCommand(String reason, List<ShortageLineCommand> lines) {
        public ShortageResolutionCommand {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    public record ShortageLineCommand(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit) { }

    public record AttemptCommand(DeliveryAttemptOutcome outcome, String failureReason, String notes,
                                 Instant attemptedAt, List<AttemptLineCommand> lines) {
        public AttemptCommand {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    public record AttemptLineCommand(UUID fulfillmentLineId, UUID skuId, BigDecimal attemptedQuantity,
                                     BigDecimal deliveredQuantity, BigDecimal rejectedQuantity,
                                     BigDecimal cancelledQuantity, String unit) {
        public AttemptLineCommand {
            deliveredQuantity = deliveredQuantity == null ? BigDecimal.ZERO : deliveredQuantity;
            rejectedQuantity = rejectedQuantity == null ? BigDecimal.ZERO : rejectedQuantity;
            cancelledQuantity = cancelledQuantity == null ? BigDecimal.ZERO : cancelledQuantity;
        }
    }

    public record PodCommand(String receiverName, Instant capturedAt, String notes,
                             UUID photoEvidenceObjectId, UUID signatureEvidenceObjectId) { }

    public record TemperatureCommand(UUID lotId, BigDecimal temperatureCelsius, String unit,
                                     String source, String evidenceMetadata, Instant recordedAt) { }
}
