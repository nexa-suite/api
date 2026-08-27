package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

import java.util.UUID;

public record SalesOrderId(UUID value) {
    public SalesOrderId { if (value == null) throw new IllegalArgumentException("Sales order id is required"); }
}
