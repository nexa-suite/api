package com.nexa.api.salescommitment.presentation.salesorder.response;

import java.math.BigDecimal;

public record SalesOrderLineResponse(String catalogItemId, String itemName, String presentation, BigDecimal quantity, String unit,
		BigDecimal unitPriceAmount, String unitPriceCurrency, BigDecimal lineSubtotal,
		String skuId, String familyId, String skuCode, String familyCode) {
	public SalesOrderLineResponse(String catalogItemId, String itemName, String presentation, BigDecimal quantity, String unit,
			BigDecimal unitPriceAmount, String unitPriceCurrency, BigDecimal lineSubtotal) {
		this(catalogItemId, itemName, presentation, quantity, unit, unitPriceAmount, unitPriceCurrency, lineSubtotal,
			null, null, null, null);
	}
}
