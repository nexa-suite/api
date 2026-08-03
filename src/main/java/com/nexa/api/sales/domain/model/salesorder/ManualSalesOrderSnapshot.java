package com.nexa.api.sales.domain.model.salesorder;

import com.nexa.api.sales.domain.model.commercial.CommercialSnapshot;
import com.nexa.api.sales.domain.model.delivery.DeliverySnapshot;
import com.nexa.api.sales.domain.model.payment.PaymentSnapshot;

import java.time.Instant;
import java.util.Objects;

/** Immutable facts copied when Sales creates an order directly without a buyer request. */
public record ManualSalesOrderSnapshot(DeliverySnapshot delivery, CommercialSnapshot commercial,
                                       PaymentSnapshot payment, Instant capturedAt, String notes) {
    public ManualSalesOrderSnapshot(DeliverySnapshot delivery, CommercialSnapshot commercial,
                                    PaymentSnapshot payment, Instant capturedAt) {
        this(delivery, commercial, payment, capturedAt, null);
    }

    public ManualSalesOrderSnapshot {
        delivery = Objects.requireNonNull(delivery, "Delivery snapshot is required");
        commercial = Objects.requireNonNull(commercial, "Commercial snapshot is required");
        payment = Objects.requireNonNull(payment, "Payment snapshot is required");
        capturedAt = Objects.requireNonNull(capturedAt, "Snapshot capture time is required");
        if (notes != null && notes.length() > 2000) {
            throw new com.nexa.api.sales.domain.exception.SalesInvariantViolation("Sales order notes are too long");
        }
        notes = notes == null || notes.isBlank() ? null : notes.trim();
    }
}
