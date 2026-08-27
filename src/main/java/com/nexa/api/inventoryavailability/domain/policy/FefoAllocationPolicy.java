package com.nexa.api.inventoryavailability.domain.policy;

import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLotStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure, deterministic FEFO policy. It has no persistence or transport dependency. */
public final class FefoAllocationPolicy {
    private FefoAllocationPolicy() { }

    public static Result allocate(List<LotSnapshot> candidates, BigDecimal requested, String unit) {
        return allocate(candidates, requested, unit, null, null, LocalDate.now());
    }

    /**
     * Selects only lots that are eligible for the requested SKU and warehouse.
     * The persistence query is an optimization; these checks remain in Domain
     * so a caller cannot turn a blocked, expired or cross-scope row into stock.
     */
    public static Result allocate(List<LotSnapshot> candidates, BigDecimal requested, String unit,
                                  String skuId, String warehouseId, LocalDate asOf) {
        Objects.requireNonNull(candidates); Objects.requireNonNull(requested);
        Objects.requireNonNull(asOf);
        if (requested.signum() <= 0) throw new IllegalArgumentException("Requested quantity must be positive");
        String normalizedUnit = normalize(unit);
        BigDecimal remaining = requested; List<Allocation> allocations = new ArrayList<>();
        var ordered = candidates.stream().filter(l -> eligible(l, normalizedUnit, skuId, warehouseId, asOf))
            .sorted(Comparator.comparing(LotSnapshot::expirationDate).thenComparing(LotSnapshot::receivedAt).thenComparing(LotSnapshot::lotId)).toList();
        for (var lot : ordered) {
            if (remaining.signum() <= 0) break;
            BigDecimal amount = remaining.min(lot.available());
            if (amount.signum() > 0) allocations.add(new Allocation(lot.lotId(), amount, lot.expirationDate(), lot.unit()));
            remaining = remaining.subtract(amount);
        }
        return new Result(List.copyOf(allocations), remaining, remaining.signum() == 0);
    }

    private static boolean eligible(LotSnapshot lot, String unit, String skuId, String warehouseId, LocalDate asOf) {
        return lot.status() == InventoryLotStatus.AVAILABLE
                && lot.available().signum() > 0
                && unit.equals(normalize(lot.unit()))
                && lot.expirationDate().isAfter(asOf)
                && (skuId == null || skuId.equals(lot.skuId()))
                && (warehouseId == null || warehouseId.equals(lot.warehouseId()));
    }

    private static String normalize(String unit) { if (unit == null || unit.isBlank()) throw new IllegalArgumentException("Unit is required"); return unit.trim().toUpperCase(java.util.Locale.ROOT); }
    public record LotSnapshot(String lotId, BigDecimal available, String unit, LocalDate expirationDate, Instant receivedAt,
                              InventoryLotStatus status, String skuId, String warehouseId) {
        public LotSnapshot(String lotId, BigDecimal available, String unit, LocalDate expirationDate, Instant receivedAt) {
            this(lotId, available, unit, expirationDate, receivedAt, InventoryLotStatus.AVAILABLE, null, null);
        }

        public LotSnapshot {
            Objects.requireNonNull(lotId);
            Objects.requireNonNull(available);
            Objects.requireNonNull(expirationDate);
            Objects.requireNonNull(receivedAt);
            Objects.requireNonNull(status);
            if (available.signum() < 0) throw new IllegalArgumentException("Available quantity cannot be negative");
        }
    }
    public record Allocation(String lotId, BigDecimal quantity, LocalDate expirationDate, String unit) { }
    public record Result(List<Allocation> allocations, BigDecimal shortage, boolean complete) { public Result { allocations = List.copyOf(allocations); } }
}
