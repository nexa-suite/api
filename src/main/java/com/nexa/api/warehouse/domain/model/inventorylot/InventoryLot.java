package com.nexa.api.warehouse.domain.model.inventorylot;

import java.math.BigDecimal;
import java.util.Objects;

/** Domain invariant for physical and reserved quantities. Persistence cannot bypass this contract. */
public final class InventoryLot {
    private final String id; private final String unit; private BigDecimal onHand; private BigDecimal reserved; private InventoryLotStatus status;
    private InventoryLot(String id, BigDecimal onHand, BigDecimal reserved, String unit, InventoryLotStatus status) {
        this.id=Objects.requireNonNull(id); this.unit=Objects.requireNonNull(unit); this.onHand=onHand; this.reserved=reserved; this.status=Objects.requireNonNull(status); validate();
    }
    public static InventoryLot rehydrate(String id, BigDecimal onHand, BigDecimal reserved, String unit, InventoryLotStatus status) { return new InventoryLot(id,onHand,reserved,unit,status); }
    public void receive(BigDecimal quantity) { requirePositive(quantity); onHand=onHand.add(quantity); status=InventoryLotStatus.AVAILABLE; validate(); }
    public void adjustIn(BigDecimal quantity) { receive(quantity); }
    public void adjustOut(BigDecimal quantity) { requirePositive(quantity); if (available().compareTo(quantity)<0) throw new IllegalStateException("Insufficient available stock"); onHand=onHand.subtract(quantity); refreshStatus(); }
    public void recordWaste(BigDecimal quantity) { adjustOut(quantity); }
    public void reserve(BigDecimal quantity) { requirePositive(quantity); if (available().compareTo(quantity)<0) throw new IllegalStateException("Insufficient available stock"); reserved=reserved.add(quantity); validate(); }
    public void releaseReservation(BigDecimal quantity) { requirePositive(quantity); if (reserved.compareTo(quantity)<0) throw new IllegalStateException("Reserved quantity cannot become negative"); reserved=reserved.subtract(quantity); validate(); }
    public void markBlocked() { status=InventoryLotStatus.BLOCKED; }
    public void markQuarantined() { status=InventoryLotStatus.QUARANTINED; }
    public void restoreAvailability() { status=InventoryLotStatus.AVAILABLE; refreshStatus(); }
    public void markExpired() { status=InventoryLotStatus.EXPIRED; }
    public BigDecimal available() { return onHand.subtract(reserved); }
    public BigDecimal onHand() { return onHand; } public BigDecimal reserved() { return reserved; } public String unit() { return unit; } public InventoryLotStatus status() { return status; }
    private void refreshStatus() { if (onHand.signum()==0) status=InventoryLotStatus.DEPLETED; else if (status==InventoryLotStatus.DEPLETED) status=InventoryLotStatus.AVAILABLE; }
    private void validate() { if (onHand==null || reserved==null || onHand.signum()<0 || reserved.signum()<0 || reserved.compareTo(onHand)>0) throw new IllegalStateException("Inventory quantity invariant violated"); }
    private static void requirePositive(BigDecimal value) { if (value==null || value.signum()<=0) throw new IllegalArgumentException("Quantity must be positive"); }
}
