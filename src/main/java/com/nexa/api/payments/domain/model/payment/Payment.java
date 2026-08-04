package com.nexa.api.payments.domain.model.payment;

import java.math.BigDecimal;
import java.util.Objects;

/** Payment aggregate; only verified provider events may mark a card payment successful. */
public final class Payment {
    private final String id;
    private final BigDecimal amount;
    private PaymentStatus status;

    private Payment(String id, BigDecimal amount, PaymentStatus status) {
        this.id = requiredId(id);
        this.amount = positiveAmount(amount, "Payment amount is invalid");
        this.status = Objects.requireNonNull(status, "Payment status is required");
    }

    public static Payment rehydrate(String id, BigDecimal amount, PaymentStatus status) {
        return new Payment(id, amount, status);
    }

    public void providerStatus(PaymentStatus next) {
        applyProviderStatus(next);
    }

    /** Applies a verified provider transition and rejects stale/regressive events. */
    public boolean applyProviderStatus(PaymentStatus next) {
        Objects.requireNonNull(next, "Payment status is required");
        if (next == status) return false;
        if (next == PaymentStatus.CREATED) throw new IllegalArgumentException("Payment cannot regress to CREATED");
        if (status == PaymentStatus.SUCCEEDED && next != PaymentStatus.REFUNDED && next != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalArgumentException("Succeeded payment cannot regress");
        }
        if ((status == PaymentStatus.REFUNDED || status == PaymentStatus.PARTIALLY_REFUNDED) && next != PaymentStatus.REFUNDED) {
            throw new IllegalArgumentException("Refunded payment cannot change to a non-refund status");
        }
        if (status == PaymentStatus.CANCELLED) throw new IllegalArgumentException("Cancelled payment cannot change status");
        if (status == PaymentStatus.PROCESSING && next == PaymentStatus.REQUIRES_ACTION) {
            throw new IllegalArgumentException("Payment provider status is stale");
        }
        status = next;
        return true;
    }
    public String id() { return id; }
    public BigDecimal amount() { return amount; }
    public PaymentStatus status() { return status; }

    private static String requiredId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Payment id is required");
        return value;
    }

    private static BigDecimal positiveAmount(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(message);
        return value;
    }
}
