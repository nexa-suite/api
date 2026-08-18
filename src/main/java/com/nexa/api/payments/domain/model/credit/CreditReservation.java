package com.nexa.api.payments.domain.model.credit;

import java.math.BigDecimal;

/** Domain lifecycle for a credit hold before it is consumed or released. */
public final class CreditReservation {
    private final String id;
    private final BigDecimal amount;
    private CreditReservationStatus status;

    private CreditReservation(String id, BigDecimal amount) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Credit reservation id is required");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Credit reservation amount is invalid");
        this.id = id;
        this.amount = amount;
        this.status = CreditReservationStatus.RESERVED;
    }

    public static CreditReservation reserve(String id, BigDecimal amount) {
        return new CreditReservation(id, amount);
    }

    public void consume() {
        requireReserved();
        status = CreditReservationStatus.CONSUMED;
    }

    public void release() {
        requireReserved();
        status = CreditReservationStatus.RELEASED;
    }

    public void expire() {
        requireReserved();
        status = CreditReservationStatus.EXPIRED;
    }

    public String id() { return id; }
    public BigDecimal amount() { return amount; }
    public CreditReservationStatus status() { return status; }

    private void requireReserved() {
        if (status != CreditReservationStatus.RESERVED) throw new IllegalStateException("Credit reservation is no longer open");
    }
}
