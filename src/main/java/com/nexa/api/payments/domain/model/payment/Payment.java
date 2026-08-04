package com.nexa.api.payments.domain.model.payment;

import java.math.BigDecimal;
import java.util.Objects;

/** Payment aggregate; only verified provider events may mark a card payment successful. */
public final class Payment {
    private final String id;
    private final BigDecimal amount;
    private PaymentStatus status;

    private Payment(String id, BigDecimal amount, PaymentStatus status) { this.id = Objects.requireNonNull(id); this.amount = amount; this.status = Objects.requireNonNull(status); }
    public static Payment rehydrate(String id, BigDecimal amount, PaymentStatus status) {
        if (amount.signum() <= 0) throw new IllegalArgumentException("Payment amount is invalid");
        return new Payment(id, amount, status);
    }
    public void providerStatus(PaymentStatus next) {
        if (next == PaymentStatus.SUCCEEDED && status == PaymentStatus.REFUNDED) throw new IllegalArgumentException("Refunded payment cannot succeed");
        if (status == PaymentStatus.SUCCEEDED && next == PaymentStatus.CREATED) throw new IllegalArgumentException("Succeeded payment cannot regress");
        status = Objects.requireNonNull(next);
    }
    public String id() { return id; }
    public BigDecimal amount() { return amount; }
    public PaymentStatus status() { return status; }
}
