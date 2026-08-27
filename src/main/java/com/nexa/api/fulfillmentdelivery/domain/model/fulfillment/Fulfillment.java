package com.nexa.api.fulfillmentdelivery.domain.model.fulfillment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure BC-06 aggregate for quantity and lifecycle invariants. Persistence
 * adapters may project it to SQL, but cannot bypass these transition rules.
 */
public final class Fulfillment {
    private final UUID id;
    private final UUID salesOrderId;
    private final UUID physicalAllocationId;
    private final Map<UUID, LineState> lines;
    private FulfillmentStatus status;
    private long version;

    private Fulfillment(UUID id, UUID salesOrderId, UUID physicalAllocationId, List<Line> lines) {
        this.id = Objects.requireNonNull(id, "Fulfillment id is required");
        this.salesOrderId = Objects.requireNonNull(salesOrderId, "Sales Order id is required");
        this.physicalAllocationId = Objects.requireNonNull(physicalAllocationId, "Physical allocation id is required");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Fulfillment requires lines");
        this.lines = new LinkedHashMap<>();
        for (Line line : lines) {
            if (this.lines.put(line.id(), new LineState(line)) != null) {
                throw new IllegalArgumentException("Fulfillment line is duplicated");
            }
        }
        this.status = FulfillmentStatus.PLANNED;
    }

    public static Fulfillment planned(UUID id, UUID salesOrderId, UUID physicalAllocationId, List<Line> lines) {
        return new Fulfillment(id, salesOrderId, physicalAllocationId, lines);
    }

    public void allocate(Map<UUID, BigDecimal> quantities) {
        requireStatus(FulfillmentStatus.PLANNED);
        for (LineState line : lines.values()) {
            BigDecimal quantity = requiredQuantity(quantities, line.id);
            if (quantity.compareTo(line.orderedQuantity) > 0) throw new IllegalArgumentException("Allocation exceeds ordered quantity");
            line.backedQuantity = quantity;
            line.allocatedQuantity = quantity;
        }
        status = FulfillmentStatus.ALLOCATED;
        version++;
    }

    public void startPicking() {
        requireStatus(FulfillmentStatus.ALLOCATED);
        status = FulfillmentStatus.PICKING;
        version++;
    }

    public void confirmPicking(Map<UUID, BigDecimal> quantities) {
        requireStatus(FulfillmentStatus.PICKING);
        boolean shortage = false;
        for (LineState line : lines.values()) {
            BigDecimal quantity = requiredQuantity(quantities, line.id);
            if (quantity.compareTo(line.allocatedQuantity) > 0) throw new IllegalArgumentException("Picking exceeds allocated quantity");
            line.pickedQuantity = quantity;
            shortage |= quantity.compareTo(line.allocatedQuantity) < 0;
        }
        status = shortage ? FulfillmentStatus.SHORTAGE : FulfillmentStatus.PICKED;
        version++;
    }

    public void pack() {
        requireStatus(FulfillmentStatus.PICKED);
        lines.values().forEach(line -> line.packedQuantity = line.pickedQuantity);
        status = FulfillmentStatus.PACKED;
        version++;
    }

    /**
     * Closes the picker discrepancy as a final unfulfilled quantity. Physical
     * release is owned by BC-05; this aggregate only records the commercial
     * consequence before packing can continue.
     */
    public void resolveShortage(Map<UUID, BigDecimal> quantities) {
        requireStatus(FulfillmentStatus.SHORTAGE);
        for (LineState line : lines.values()) {
            BigDecimal quantity = requiredQuantity(quantities, line.id);
            BigDecimal shortage = line.allocatedQuantity.subtract(line.pickedQuantity);
            if (quantity.compareTo(shortage) != 0) throw new IllegalArgumentException("Shortage resolution must cover the exact discrepancy");
            line.unfulfilledQuantity = quantity;
        }
        status = FulfillmentStatus.PICKED;
        version++;
    }

    public void stage() {
        requireStatus(FulfillmentStatus.PACKED);
        lines.values().forEach(line -> line.stagedQuantity = line.packedQuantity);
        status = FulfillmentStatus.STAGED;
        version++;
    }

    public void readyForDispatch() {
        requireStatus(FulfillmentStatus.STAGED);
        status = FulfillmentStatus.READY_FOR_DISPATCH;
        version++;
    }

    public void handOver() {
        requireStatus(FulfillmentStatus.READY_FOR_DISPATCH);
        lines.values().forEach(line -> line.dispatchedQuantity = line.stagedQuantity);
        status = FulfillmentStatus.HANDED_OVER;
        version++;
    }

    public void recordOutcome(Map<UUID, Outcome> outcomes) {
        requireStatus(FulfillmentStatus.HANDED_OVER);
        if (outcomes == null || outcomes.isEmpty()) throw new IllegalArgumentException("Delivery outcome is required");
        for (Outcome outcome : outcomes.values()) {
            LineState line = requireLine(outcome.lineId());
            BigDecimal delivered = nonNegative(outcome.deliveredQuantity(), "Delivered quantity is invalid");
            BigDecimal rejected = nonNegative(outcome.rejectedQuantity(), "Rejected quantity is invalid");
            BigDecimal cancelled = nonNegative(outcome.cancelledQuantity(), "Cancelled quantity is invalid");
            if (delivered.add(rejected).add(cancelled).compareTo(line.remainingBeforeOutcome()) > 0) {
                throw new IllegalArgumentException("Delivery outcome exceeds unresolved quantity");
            }
            line.deliveredQuantity = line.deliveredQuantity.add(delivered);
            line.rejectedQuantity = line.rejectedQuantity.add(rejected);
            line.cancelledQuantity = line.cancelledQuantity.add(cancelled);
        }
        if (unresolvedQuantity().signum() == 0) status = FulfillmentStatus.COMPLETED;
        version++;
    }

    public void hold() {
        if (status == FulfillmentStatus.COMPLETED || status == FulfillmentStatus.CANCELLED) {
            throw new IllegalStateException("Terminal fulfillment cannot be held");
        }
        status = FulfillmentStatus.HOLD;
        version++;
    }

    public void cancel() {
        if (status == FulfillmentStatus.HANDED_OVER || status == FulfillmentStatus.COMPLETED
                || status == FulfillmentStatus.CANCELLED) {
            throw new IllegalStateException("Fulfillment cannot be cancelled in its current state");
        }
        status = FulfillmentStatus.CANCELLED;
        version++;
    }

    public BigDecimal unresolvedQuantity() {
        return lines.values().stream().map(LineState::commercialRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public UUID id() { return id; }
    public UUID salesOrderId() { return salesOrderId; }
    public UUID physicalAllocationId() { return physicalAllocationId; }
    public FulfillmentStatus status() { return status; }
    public long version() { return version; }
    public List<LineSnapshot> lines() {
        return Collections.unmodifiableList(lines.values().stream().map(LineState::snapshot).toList());
    }

    private LineState requireLine(UUID lineId) {
        LineState line = lines.get(lineId);
        if (line == null) throw new IllegalArgumentException("Fulfillment line does not exist");
        return line;
    }

    private static BigDecimal requiredQuantity(Map<UUID, BigDecimal> quantities, UUID lineId) {
        if (quantities == null || !quantities.containsKey(lineId)) throw new IllegalArgumentException("Quantity is required for every fulfillment line");
        return nonNegative(quantities.get(lineId), "Quantity is invalid");
    }

    private static BigDecimal nonNegative(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(message);
        return value;
    }

    private void requireStatus(FulfillmentStatus expected) {
        if (status != expected) throw new IllegalStateException("Fulfillment must be " + expected + " but is " + status);
    }

    private void requireOneOf(FulfillmentStatus first, FulfillmentStatus second) {
        if (status != first && status != second) throw new IllegalStateException("Fulfillment transition is invalid from " + status);
    }

    public record Line(UUID id, UUID skuId, String catalogItemId, BigDecimal orderedQuantity, String unit) {
        public Line {
            Objects.requireNonNull(id, "Fulfillment line id is required");
            Objects.requireNonNull(skuId, "SKU id is required");
            if (catalogItemId == null || catalogItemId.isBlank() || orderedQuantity == null || orderedQuantity.signum() <= 0
                    || unit == null || unit.isBlank()) throw new IllegalArgumentException("Fulfillment line is invalid");
            catalogItemId = catalogItemId.trim();
            unit = unit.trim();
        }
    }

    public record Outcome(UUID lineId, BigDecimal deliveredQuantity, BigDecimal rejectedQuantity,
                          BigDecimal cancelledQuantity) {
        public Outcome {
            Objects.requireNonNull(lineId, "Fulfillment line id is required");
        }
    }

    public record LineSnapshot(UUID id, UUID skuId, String catalogItemId, BigDecimal orderedQuantity,
                               BigDecimal backedQuantity, BigDecimal allocatedQuantity, BigDecimal pickedQuantity,
                               BigDecimal packedQuantity, BigDecimal stagedQuantity, BigDecimal dispatchedQuantity,
                               BigDecimal deliveredQuantity, BigDecimal rejectedQuantity,
                               BigDecimal cancelledQuantity, BigDecimal unfulfilledQuantity,
                               BigDecimal remainingQuantity, String unit) { }

    private static final class LineState {
        private final UUID id;
        private final UUID skuId;
        private final String catalogItemId;
        private final BigDecimal orderedQuantity;
        private final String unit;
        private BigDecimal backedQuantity = BigDecimal.ZERO;
        private BigDecimal allocatedQuantity = BigDecimal.ZERO;
        private BigDecimal pickedQuantity = BigDecimal.ZERO;
        private BigDecimal packedQuantity = BigDecimal.ZERO;
        private BigDecimal stagedQuantity = BigDecimal.ZERO;
        private BigDecimal dispatchedQuantity = BigDecimal.ZERO;
        private BigDecimal deliveredQuantity = BigDecimal.ZERO;
        private BigDecimal rejectedQuantity = BigDecimal.ZERO;
        private BigDecimal cancelledQuantity = BigDecimal.ZERO;
        private BigDecimal unfulfilledQuantity = BigDecimal.ZERO;

        private LineState(Line line) {
            this.id = line.id(); this.skuId = line.skuId(); this.catalogItemId = line.catalogItemId();
            this.orderedQuantity = line.orderedQuantity(); this.unit = line.unit();
        }

        private BigDecimal remainingBeforeOutcome() {
            return orderedQuantity.subtract(unfulfilledQuantity).subtract(deliveredQuantity)
                    .subtract(rejectedQuantity).subtract(cancelledQuantity).max(BigDecimal.ZERO);
        }

        private BigDecimal commercialRemaining() {
            return orderedQuantity.subtract(unfulfilledQuantity).subtract(deliveredQuantity)
                    .subtract(rejectedQuantity).subtract(cancelledQuantity).max(BigDecimal.ZERO);
        }

        private LineSnapshot snapshot() {
            return new LineSnapshot(id, skuId, catalogItemId, orderedQuantity, backedQuantity, allocatedQuantity,
                    pickedQuantity, packedQuantity, stagedQuantity, dispatchedQuantity, deliveredQuantity,
                    rejectedQuantity, cancelledQuantity, unfulfilledQuantity, commercialRemaining(), unit);
        }
    }
}
