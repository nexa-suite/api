package com.nexa.api.warehouse.domain.policy;

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
        Objects.requireNonNull(candidates); Objects.requireNonNull(requested);
        if (requested.signum() <= 0) throw new IllegalArgumentException("Requested quantity must be positive");
        String normalizedUnit = normalize(unit);
        BigDecimal remaining = requested; List<Allocation> allocations = new ArrayList<>();
        var ordered = candidates.stream().filter(l -> l.available().signum() > 0 && normalizedUnit.equals(normalize(l.unit())))
            .sorted(Comparator.comparing(LotSnapshot::expirationDate).thenComparing(LotSnapshot::receivedAt).thenComparing(LotSnapshot::lotId)).toList();
        for (var lot : ordered) {
            if (remaining.signum() <= 0) break;
            BigDecimal amount = remaining.min(lot.available());
            if (amount.signum() > 0) allocations.add(new Allocation(lot.lotId(), amount, lot.expirationDate(), lot.unit()));
            remaining = remaining.subtract(amount);
        }
        return new Result(List.copyOf(allocations), remaining, remaining.signum() == 0);
    }

    private static String normalize(String unit) { if (unit == null || unit.isBlank()) throw new IllegalArgumentException("Unit is required"); return unit.trim().toUpperCase(java.util.Locale.ROOT); }
    public record LotSnapshot(String lotId, BigDecimal available, String unit, LocalDate expirationDate, Instant receivedAt) {
        public LotSnapshot { Objects.requireNonNull(lotId); Objects.requireNonNull(available); Objects.requireNonNull(expirationDate); Objects.requireNonNull(receivedAt); if (available.signum() < 0) throw new IllegalArgumentException("Available quantity cannot be negative"); }
    }
    public record Allocation(String lotId, BigDecimal quantity, LocalDate expirationDate, String unit) { }
    public record Result(List<Allocation> allocations, BigDecimal shortage, boolean complete) { public Result { allocations = List.copyOf(allocations); } }
}
