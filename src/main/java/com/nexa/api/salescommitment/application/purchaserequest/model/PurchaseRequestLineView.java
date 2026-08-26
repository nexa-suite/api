package com.nexa.api.salescommitment.application.purchaserequest.model;

import java.math.BigDecimal;

public record PurchaseRequestLineView(String id, String catalogItemId, String itemName, String presentation,
		BigDecimal quantity, String unit, BigDecimal unitPriceAmount, String unitPriceCurrency, String notes, long version) { }
