package com.nexa.api.sales.presentation.purchaserequest.response;

import java.math.BigDecimal;

public record PurchaseRequestLineResponse(String id, String catalogItemId, String itemName, String presentation,
		BigDecimal quantity, String unit, BigDecimal unitPriceAmount, String unitPriceCurrency, String notes, long version) { }
