package com.nexa.api.payments.domain.model.payment;

public enum PaymentStatus {
    CREATED,
    REQUIRES_ACTION,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED
}
