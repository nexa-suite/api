package com.nexa.api.payments.domain.model.receivable;

import java.math.BigDecimal;
import java.util.Objects;

/** Receivable aggregate; payment status never bypasses this balance authority. */
public final class Receivable {
    private final String id;
    private final BigDecimal amount;
    private BigDecimal amountPaid;
    private ReceivableStatus status;

    private Receivable(String id, BigDecimal amount, BigDecimal amountPaid, ReceivableStatus status) {
        this.id = Objects.requireNonNull(id); this.amount = amount; this.amountPaid = amountPaid; this.status = status;
    }

    public static Receivable rehydrate(String id, BigDecimal amount, BigDecimal amountPaid, ReceivableStatus status) {
        if (amount.signum() <= 0 || amountPaid.signum() < 0 || amountPaid.compareTo(amount) > 0) throw new IllegalArgumentException("Receivable balance is invalid");
        return new Receivable(id, amount, amountPaid, Objects.requireNonNull(status));
    }

    public void allocate(BigDecimal allocation) {
        if (allocation == null || allocation.signum() <= 0 || amountPaid.add(allocation).compareTo(amount) > 0) throw new IllegalArgumentException("Receivable allocation exceeds balance");
        amountPaid = amountPaid.add(allocation);
        status = amountPaid.compareTo(amount) == 0 ? ReceivableStatus.PAID : ReceivableStatus.PARTIALLY_PAID;
    }

    public String id() { return id; }
    public BigDecimal amount() { return amount; }
    public BigDecimal amountPaid() { return amountPaid; }
    public BigDecimal remaining() { return amount.subtract(amountPaid); }
    public ReceivableStatus status() { return status; }
}
