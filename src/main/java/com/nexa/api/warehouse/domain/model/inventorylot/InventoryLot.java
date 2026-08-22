package com.nexa.api.warehouse.domain.model.inventorylot;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/** Domain invariant for physical and reserved quantities. Persistence cannot bypass this contract. */
public final class InventoryLot {
    private final String id;
    private final String unit;
    private BigDecimal onHand;
    private BigDecimal reserved;
    private InventoryLotStatus status;

    private InventoryLot(String id, BigDecimal onHand, BigDecimal reserved, String unit, InventoryLotStatus status) {
        this.id = requireText(id, "Lot id");
        this.unit = normalizeUnit(unit);
        this.onHand = Objects.requireNonNull(onHand, "On-hand quantity is required");
        this.reserved = Objects.requireNonNull(reserved, "Reserved quantity is required");
        this.status = Objects.requireNonNull(status, "Lot status is required");
        validate();
    }

    public static InventoryLot rehydrate(String id, BigDecimal onHand, BigDecimal reserved, String unit,
                                         InventoryLotStatus status) {
        return new InventoryLot(id, onHand, reserved, unit, status);
    }

    public void receive(BigDecimal quantity) {
        requirePositive(quantity);
        requireOperational();
        onHand = onHand.add(quantity);
        status = InventoryLotStatus.AVAILABLE;
        validate();
    }

    public void adjustIn(BigDecimal quantity) { receive(quantity); }

    public void adjustOut(BigDecimal quantity) {
        requirePositive(quantity);
        requireOperational();
        if (available().compareTo(quantity) < 0) throw new IllegalStateException("Insufficient available stock");
        onHand = onHand.subtract(quantity);
        refreshStatus();
        validate();
    }

    public void recordWaste(BigDecimal quantity) { adjustOut(quantity); }

    public void reserve(BigDecimal quantity) {
        requirePositive(quantity);
        requireOperational();
        if (available().compareTo(quantity) < 0) throw new IllegalStateException("Insufficient available stock");
        reserved = reserved.add(quantity);
        validate();
    }

    public void releaseReservation(BigDecimal quantity) {
        requirePositive(quantity);
        if (reserved.compareTo(quantity) < 0) throw new IllegalStateException("Reserved quantity cannot become negative");
        reserved = reserved.subtract(quantity);
        validate();
    }

    /** Consumes stock that was previously reserved by a fulfillment. */
    public void consume(BigDecimal quantity) {
        requirePositive(quantity);
        if (status != InventoryLotStatus.AVAILABLE || reserved.compareTo(quantity) < 0
                || onHand.compareTo(quantity) < 0) {
            throw new IllegalStateException("Reserved stock cannot be consumed");
        }
        onHand = onHand.subtract(quantity);
        reserved = reserved.subtract(quantity);
        refreshStatus();
        validate();
    }

    public void markBlocked() {
        requireUnreservedForStateChange();
        if (status != InventoryLotStatus.AVAILABLE) throw invalidTransition();
        status = InventoryLotStatus.BLOCKED;
        validate();
    }

    public void markQuarantined() {
        requireUnreservedForStateChange();
        if (status != InventoryLotStatus.AVAILABLE) throw invalidTransition();
        status = InventoryLotStatus.QUARANTINED;
        validate();
    }

    public void markHold() {
        requireUnreservedForStateChange();
        if (status != InventoryLotStatus.AVAILABLE) throw invalidTransition();
        status = InventoryLotStatus.HOLD;
        validate();
    }

    public void restoreAvailability() {
        if (status != InventoryLotStatus.BLOCKED && status != InventoryLotStatus.QUARANTINED && status != InventoryLotStatus.HOLD) {
            throw invalidTransition();
        }
        if (onHand.signum() == 0) throw invalidTransition();
        status = InventoryLotStatus.AVAILABLE;
        validate();
    }

    public void markExpired() {
        if (reserved.signum() > 0 || status == InventoryLotStatus.DEPLETED) throw invalidTransition();
        status = InventoryLotStatus.EXPIRED;
        validate();
    }

    public BigDecimal available() { return onHand.subtract(reserved); }
    public String id() { return id; }
    public BigDecimal onHand() { return onHand; }
    public BigDecimal reserved() { return reserved; }
    public String unit() { return unit; }
    public InventoryLotStatus status() { return status; }

    private void requireOperational() {
        if (status != InventoryLotStatus.AVAILABLE) throw invalidTransition();
    }

    private void requireUnreservedForStateChange() {
        if (reserved.signum() > 0) throw new IllegalStateException("Reserved lot cannot change availability state");
    }

    private IllegalStateException invalidTransition() {
        return new IllegalStateException("Inventory lot transition is invalid for status " + status);
    }

    private void refreshStatus() {
        if (onHand.signum() == 0) status = InventoryLotStatus.DEPLETED;
        else if (status == InventoryLotStatus.DEPLETED) status = InventoryLotStatus.AVAILABLE;
    }

    private void validate() {
        if (onHand.signum() < 0 || reserved.signum() < 0 || reserved.compareTo(onHand) > 0) {
            throw new IllegalStateException("Inventory quantity invariant violated");
        }
        if (status == InventoryLotStatus.DEPLETED && onHand.signum() != 0) {
            throw new IllegalStateException("Depleted lot must have zero on-hand quantity");
        }
    }

    private static String normalizeUnit(String value) {
        String unit = requireText(value, "Unit").toUpperCase(Locale.ROOT);
        if (!unit.matches("[A-Z0-9._/-]+")) throw new IllegalArgumentException("Unit is invalid");
        return unit;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static void requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Quantity must be positive");
    }
}
