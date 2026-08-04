package com.nexa.api.warehouse.domain.model.inventoryreservation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Aggregate state machine for an inventory reservation. */
public final class InventoryReservation {
    private final String id;
    private InventoryReservationStatus status;
    private List<Allocation> allocations;

    private InventoryReservation(String id, InventoryReservationStatus status, List<Allocation> allocations) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Reservation id is required");
        this.id = id;
        this.status = Objects.requireNonNull(status, "Reservation status is required");
        this.allocations = allocations == null ? List.of() : List.copyOf(allocations);
        validateAllocations();
    }

    public static InventoryReservation rehydrate(String id, InventoryReservationStatus status,
                                                  List<Allocation> allocations) {
        return new InventoryReservation(id, status, allocations);
    }

    public void reserve(List<Allocation> values) {
        requireStatus(InventoryReservationStatus.PENDING);
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Reservation requires allocations");
        allocations = List.copyOf(values);
        validateAllocations();
        status = InventoryReservationStatus.RESERVED;
    }

    public void recordShortage() {
        requireStatus(InventoryReservationStatus.PENDING);
        status = InventoryReservationStatus.SHORTAGE;
    }

    public void release() {
        requireStatus(InventoryReservationStatus.RESERVED);
        status = InventoryReservationStatus.RELEASED;
    }

    public void expire() {
        requireStatus(InventoryReservationStatus.RESERVED);
        status = InventoryReservationStatus.EXPIRED;
    }

    public void consume() {
        requireStatus(InventoryReservationStatus.RESERVED);
        status = InventoryReservationStatus.CONSUMED;
    }

    public String id() { return id; }
    public InventoryReservationStatus status() { return status; }
    public List<Allocation> allocations() { return allocations; }

    private void requireStatus(InventoryReservationStatus expected) {
        if (status != expected) throw new IllegalStateException("Reservation transition is invalid from " + status);
    }

    private void validateAllocations() {
        Set<String> lotIds = new HashSet<>();
        for (Allocation allocation : allocations) {
            if (allocation == null || allocation.quantity().signum() <= 0 || !lotIds.add(allocation.lotId())) {
                throw new IllegalArgumentException("Reservation allocations are invalid");
            }
        }
    }

    public record Allocation(String lotId, BigDecimal quantity) {
        public Allocation {
            if (lotId == null || lotId.isBlank() || quantity == null || quantity.signum() <= 0) {
                throw new IllegalArgumentException("Reservation allocation is invalid");
            }
        }
    }
}
