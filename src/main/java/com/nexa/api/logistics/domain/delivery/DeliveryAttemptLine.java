package com.nexa.api.logistics.domain.delivery;

import java.math.BigDecimal;

public record DeliveryAttemptLine(String catalogItemId, BigDecimal quantity, String unit) {
    public DeliveryAttemptLine {
        if (catalogItemId == null || catalogItemId.isBlank() || quantity == null
                || quantity.signum() <= 0 || unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("Delivery attempt line is incomplete");
        }
        catalogItemId = catalogItemId.trim();
        unit = unit.trim();
    }
}
