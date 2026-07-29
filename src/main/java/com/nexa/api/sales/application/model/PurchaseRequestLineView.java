package com.nexa.api.sales.application.model;

import java.math.BigDecimal;

public record PurchaseRequestLineView(String id, String catalogItemId, String itemName, String presentation,
		BigDecimal quantity, String unit, BigDecimal unitPriceAmount, String unitPriceCurrency, String notes, long version) { }
