package com.nexa.api.sales.domain.model.salesorder;

import java.math.BigDecimal;
import java.util.Objects;

public record SalesOrderLine(String catalogItemId, String itemNameSnapshot, BigDecimal quantity,
		BigDecimal unitPriceAmount, String unitPriceCurrency) {
	public SalesOrderLine {
		if (catalogItemId == null || catalogItemId.isBlank() || itemNameSnapshot == null || itemNameSnapshot.isBlank()) throw new SalesOrderInvariantViolation("Sales order line snapshot is incomplete");
		quantity = Objects.requireNonNull(quantity); unitPriceAmount = Objects.requireNonNull(unitPriceAmount); unitPriceCurrency = Objects.requireNonNull(unitPriceCurrency).trim().toUpperCase(java.util.Locale.ROOT);
		if (quantity.signum() <= 0 || unitPriceAmount.signum() < 0 || unitPriceCurrency.length() != 3) throw new SalesOrderInvariantViolation("Sales order line values are invalid");
	}
}
