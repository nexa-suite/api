package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

import java.util.UUID;

public record ClientAccountId(UUID value) {
    public ClientAccountId { if (value == null) throw new IllegalArgumentException("Client account id is required"); }
}
