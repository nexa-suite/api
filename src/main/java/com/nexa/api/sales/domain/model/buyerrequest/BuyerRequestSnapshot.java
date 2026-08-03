package com.nexa.api.sales.domain.model.buyerrequest;

import com.nexa.api.sales.domain.model.commercial.CommercialSnapshot;
import com.nexa.api.sales.domain.model.delivery.DeliveryAddressSnapshot;
import com.nexa.api.sales.domain.model.delivery.DeliverySnapshot;
import com.nexa.api.sales.domain.model.delivery.RouteSnapshot;
import com.nexa.api.sales.domain.model.delivery.WarehouseSnapshot;
import com.nexa.api.sales.domain.model.payment.PaymentSnapshot;

import java.time.Instant;
import java.util.Objects;

/** Immutable point-in-time facts copied into a Buyer Request. */
public record BuyerRequestSnapshot(DeliverySnapshot delivery, CommercialSnapshot commercial,
                                   PaymentSnapshot payment, Instant capturedAt, String comments) {
    public BuyerRequestSnapshot(DeliverySnapshot delivery, CommercialSnapshot commercial,
                                PaymentSnapshot payment, Instant capturedAt) {
        this(delivery, commercial, payment, capturedAt, null);
    }

    public BuyerRequestSnapshot {
        delivery = Objects.requireNonNull(delivery, "Delivery snapshot is required");
        commercial = Objects.requireNonNull(commercial, "Commercial snapshot is required");
        payment = Objects.requireNonNull(payment, "Payment snapshot is required");
        capturedAt = Objects.requireNonNull(capturedAt, "Snapshot capture time is required");
        if (comments != null && comments.length() > 2000) {
            throw new com.nexa.api.sales.domain.exception.SalesInvariantViolation("Buyer request comments are too long");
        }
        comments = comments == null || comments.isBlank() ? null : comments.trim();
    }

    public DeliveryAddressSnapshot address() { return delivery.address(); }
    public RouteSnapshot route() { return delivery.route(); }
    public WarehouseSnapshot warehouse() { return delivery.warehouse(); }
}
