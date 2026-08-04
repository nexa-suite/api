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
        this.id = Objects.requireNonNull(id); this.limit = limit; this.exposure = exposure; this.reserved = reserved;
    }

    public static CreditAccount rehydrate(String id, BigDecimal limit, BigDecimal exposure, BigDecimal reserved) {
        if (limit.signum() < 0 || exposure.signum() < 0 || reserved.signum() < 0 || exposure.add(reserved).compareTo(limit) > 0) throw new IllegalArgumentException("Credit account balance is invalid");
        return new CreditAccount(id, limit, exposure, reserved);
    }

    public void reserve(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || available().compareTo(amount) < 0) throw new IllegalArgumentException("Credit limit exceeded");
        reserved = reserved.add(amount);
    }

    public void consumeReservation(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || reserved.compareTo(amount) < 0) throw new IllegalArgumentException("Credit reservation is invalid");
        reserved = reserved.subtract(amount); exposure = exposure.add(amount);
    }

    public BigDecimal available() { return limit.subtract(exposure).subtract(reserved); }
    public String id() { return id; }
    public BigDecimal limit() { return limit; }
    public BigDecimal exposure() { return exposure; }
    public BigDecimal reserved() { return reserved; }
}
