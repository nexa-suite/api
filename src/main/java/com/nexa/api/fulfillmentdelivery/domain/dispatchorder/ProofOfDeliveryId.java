package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

import java.util.UUID;

public record ProofOfDeliveryId(UUID value) {
    public ProofOfDeliveryId { if (value == null) throw new IllegalArgumentException("Proof of delivery id is required"); }
}
