package com.nexa.api.payments.domain.model.credit;

import java.math.BigDecimal;
import java.util.Objects;

/** Credit account policy. Exposure and reservations are separate balances. */
public final class CreditAccount {
    private final String id;
    private final BigDecimal limit;
    private BigDecimal exposure;
    private BigDecimal reserved;

    private CreditAccount(String id, BigDecimal limit, BigDecimal exposure, BigDecimal reserved) {
        this.id = requiredId(id);
        this.limit = nonNegative(limit, "Credit limit is invalid");
        this.exposure = nonNegative(exposure, "Credit exposure is invalid");
        this.reserved = nonNegative(reserved, "Credit reservation is invalid");
        if (exposure.add(reserved).compareTo(limit) > 0) throw new IllegalArgumentException("Credit account balance is invalid");
    }

    public static CreditAccount rehydrate(String id, BigDecimal limit, BigDecimal exposure, BigDecimal reserved) {
        return new CreditAccount(id, limit, exposure, reserved);
    }

    public void reserve(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || available().compareTo(amount) < 0) throw new IllegalArgumentException("Credit limit exceeded");
        reserved = reserved.add(amount);
    }

    public void consume(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || available().compareTo(amount) < 0) throw new IllegalArgumentException("Credit limit exceeded");
        exposure = exposure.add(amount);
    }

    public void consumeReservation(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || reserved.compareTo(amount) < 0) throw new IllegalArgumentException("Credit reservation is invalid");
        reserved = reserved.subtract(amount); exposure = exposure.add(amount);
    }

    public void releaseReservation(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || reserved.compareTo(amount) < 0) throw new IllegalArgumentException("Credit reservation is invalid");
        reserved = reserved.subtract(amount);
    }

    public BigDecimal available() { return limit.subtract(exposure).subtract(reserved); }
    public String id() { return id; }
    public BigDecimal limit() { return limit; }
    public BigDecimal exposure() { return exposure; }
    public BigDecimal reserved() { return reserved; }

    private static String requiredId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Credit account id is required");
        return value;
    }

    private static BigDecimal nonNegative(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(message);
        return value;
    }
}
