package com.nexa.api.catalogcommercialpolicy.application.model;

import java.math.BigDecimal;

public record CatalogItemSnapshot(String catalogItemId, String itemName, String presentation,
        BigDecimal unitPriceAmount, String unitPriceCurrency) {
    public CatalogItemSnapshot {
        if (unitPriceAmount == null || unitPriceAmount.signum() < 0 || unitPriceAmount.scale() > 2) {
            throw new IllegalArgumentException("Catalog snapshot price is invalid");
        }
    }
}
