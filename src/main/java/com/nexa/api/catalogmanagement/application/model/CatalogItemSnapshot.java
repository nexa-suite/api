package com.nexa.api.catalogmanagement.application.model;

import java.math.BigDecimal;

public record CatalogItemSnapshot(String catalogItemId, String itemName, String presentation,
        BigDecimal unitPriceAmount, String unitPriceCurrency) { }
