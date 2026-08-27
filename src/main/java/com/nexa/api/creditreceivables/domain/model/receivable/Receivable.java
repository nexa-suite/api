package com.nexa.api.creditreceivables.domain.model.receivable;

import java.math.BigDecimal;
import java.util.Objects;

/** Receivable aggregate; payment status never bypasses this balance authority. */
public final class Receivable {
    private final String id;
    private final BigDecimal amount;
    private BigDecimal amountPaid;
    private ReceivableStatus status;

    private Receivable(String id, BigDecimal amount, BigDecimal amountPaid, ReceivableStatus status) {
        this.id = requiredId(id);
        this.amount = positiveAmount(amount);
        this.amountPaid = nonNegative(amountPaid);
        this.status = Objects.requireNonNull(status, "Receivable status is required");
        validateStatus(this.amount, this.amountPaid, this.status);
    }

    public static Receivable rehydrate(String id, BigDecimal amount, BigDecimal amountPaid, ReceivableStatus status) {
        return new Receivable(id, amount, amountPaid, Objects.requireNonNull(status));
    }

    public void allocate(BigDecimal allocation) {
        if (status == ReceivableStatus.VOID || status == ReceivableStatus.PAID) throw new IllegalArgumentException("Receivable is not payable");
        if (allocation == null || allocation.signum() <= 0 || amountPaid.add(allocation).compareTo(amount) > 0) throw new IllegalArgumentException("Receivable allocation exceeds balance");
        amountPaid = amountPaid.add(allocation);
        status = amountPaid.compareTo(amount) == 0 ? ReceivableStatus.PAID : ReceivableStatus.PARTIALLY_PAID;
    }

    public String id() { return id; }
    public BigDecimal amount() { return amount; }
    public BigDecimal amountPaid() { return amountPaid; }
    public BigDecimal remaining() { return amount.subtract(amountPaid); }
    public ReceivableStatus status() { return status; }

    private static String requiredId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Receivable id is required");
        return value;
    }

    private static BigDecimal positiveAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Receivable amount is invalid");
        return value;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("Receivable amount paid is invalid");
        return value;
    }

    private static void validateStatus(BigDecimal amount, BigDecimal paid, ReceivableStatus status) {
        switch (status) {
            case OPEN -> { if (paid.signum() != 0) throw new IllegalArgumentException("Open receivable has an invalid paid balance"); }
            case PARTIALLY_PAID -> { if (paid.signum() <= 0 || paid.compareTo(amount) >= 0) throw new IllegalArgumentException("Partially paid receivable has an invalid balance"); }
            case PAID -> { if (paid.compareTo(amount) != 0) throw new IllegalArgumentException("Paid receivable has an invalid balance"); }
            case VOID -> { if (paid.signum() != 0) throw new IllegalArgumentException("Void receivable has an invalid paid balance"); }
            case OVERDUE -> { if (paid.compareTo(amount) >= 0) throw new IllegalArgumentException("Overdue receivable has an invalid balance"); }
        }
    }
}
