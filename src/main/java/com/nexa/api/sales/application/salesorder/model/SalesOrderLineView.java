package com.nexa.api.sales.application.salesorder.model;

import java.math.BigDecimal;

public record SalesOrderLineView(String catalogItemId, String itemName, String presentation, BigDecimal quantity, String unit,
		BigDecimal unitPriceAmount, String unitPriceCurrency, BigDecimal lineSubtotal) { }
