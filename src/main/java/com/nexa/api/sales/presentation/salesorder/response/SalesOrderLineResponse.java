package com.nexa.api.sales.presentation.salesorder.response;

import java.math.BigDecimal;

public record SalesOrderLineResponse(String catalogItemId, String itemName, BigDecimal quantity, String unit,
		BigDecimal unitPriceAmount, String unitPriceCurrency) { }
