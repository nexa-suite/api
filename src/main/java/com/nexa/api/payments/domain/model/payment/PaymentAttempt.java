package com.nexa.api.payments.domain.model.payment;

import java.util.Objects;

/** Attempt aggregate used to record one provider or bank-transfer attempt. */
public final class PaymentAttempt {
    private final String id;
    private final int number;
    private PaymentStatus status;

    private PaymentAttempt(String id, int number, PaymentStatus status) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Payment attempt id is required");
        if (number < 1) throw new IllegalArgumentException("Payment attempt number is invalid");
        this.id = id;
        this.number = number;
        this.status = Objects.requireNonNull(status, "Payment attempt status is required");
    }

    public static PaymentAttempt rehydrate(String id, int number, PaymentStatus status) {
        return new PaymentAttempt(id, number, status);
    }

    public void applyStatus(PaymentStatus next) {
        Objects.requireNonNull(next, "Payment attempt status is required");
        if (status == PaymentStatus.SUCCEEDED && next != PaymentStatus.SUCCEEDED) {
            throw new IllegalArgumentException("Succeeded payment attempt cannot regress");
        }
        status = next;
    }

    public String id() { return id; }
    public int number() { return number; }
    public PaymentStatus status() { return status; }
}
