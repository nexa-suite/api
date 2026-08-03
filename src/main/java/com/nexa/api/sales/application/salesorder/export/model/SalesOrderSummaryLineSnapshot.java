package com.nexa.api.sales.application.salesorder.export.model;

import java.math.BigDecimal;

public record SalesOrderSummaryLineSnapshot(String catalogItemId, String itemName, String presentation,
		BigDecimal quantity, String unit, BigDecimal unitPriceAmount, String unitPriceCurrency,
		BigDecimal lineSubtotal) { }
