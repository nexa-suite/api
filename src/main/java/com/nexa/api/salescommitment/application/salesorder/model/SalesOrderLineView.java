package com.nexa.api.salescommitment.application.salesorder.model;

import java.math.BigDecimal;

public record SalesOrderLineView(String catalogItemId, String itemName, String presentation, BigDecimal quantity, String unit,
		BigDecimal unitPriceAmount, String unitPriceCurrency, BigDecimal lineSubtotal,
		String skuId, String familyId, String skuCode, String familyCode) {
	public SalesOrderLineView(String catalogItemId, String itemName, String presentation, BigDecimal quantity, String unit,
			BigDecimal unitPriceAmount, String unitPriceCurrency, BigDecimal lineSubtotal) {
		this(catalogItemId, itemName, presentation, quantity, unit, unitPriceAmount, unitPriceCurrency, lineSubtotal,
				null, null, null, null);
	}
}
