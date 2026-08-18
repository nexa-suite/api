package com.nexa.api.sales.domain.model.salesorder;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record SalesOrderLine(String catalogItemId, String itemNameSnapshot, String presentationSnapshot,
		BigDecimal quantity, String unit, BigDecimal unitPriceAmount, String unitPriceCurrency, BigDecimal lineSubtotal,
		UUID sellableSkuId, UUID productFamilyId, String skuCodeSnapshot, String productFamilyCodeSnapshot) {
	public SalesOrderLine(String catalogItemId, String itemNameSnapshot, BigDecimal quantity,
			BigDecimal unitPriceAmount, String unitPriceCurrency) {
		this(catalogItemId, itemNameSnapshot, itemNameSnapshot, quantity, "unit", unitPriceAmount, unitPriceCurrency,
				quantity.multiply(unitPriceAmount), null, null, null, null);
	}
	public SalesOrderLine(String catalogItemId, String itemNameSnapshot, BigDecimal quantity, String unit,
			BigDecimal unitPriceAmount, String unitPriceCurrency) {
		this(catalogItemId, itemNameSnapshot, itemNameSnapshot, quantity, unit, unitPriceAmount, unitPriceCurrency,
				quantity.multiply(unitPriceAmount), null, null, null, null);
	}
	public SalesOrderLine(String catalogItemId, String itemNameSnapshot, String presentationSnapshot,
			BigDecimal quantity, String unit, BigDecimal unitPriceAmount, String unitPriceCurrency, BigDecimal lineSubtotal) {
		this(catalogItemId, itemNameSnapshot, presentationSnapshot, quantity, unit, unitPriceAmount, unitPriceCurrency,
			lineSubtotal, null, null, null, null);
	}
	public SalesOrderLine {
		if (catalogItemId == null || catalogItemId.isBlank() || itemNameSnapshot == null || itemNameSnapshot.isBlank()
				|| presentationSnapshot == null || presentationSnapshot.isBlank() || unit == null || unit.isBlank()) throw new SalesOrderInvariantViolation("Sales order line snapshot is incomplete");
		quantity = Objects.requireNonNull(quantity); unitPriceAmount = Objects.requireNonNull(unitPriceAmount); unitPriceCurrency = Objects.requireNonNull(unitPriceCurrency).trim().toUpperCase(java.util.Locale.ROOT); lineSubtotal = Objects.requireNonNull(lineSubtotal);
		if (quantity.signum() <= 0 || unitPriceAmount.signum() < 0 || lineSubtotal.signum() < 0 || unitPriceCurrency.length() != 3) throw new SalesOrderInvariantViolation("Sales order line values are invalid");
		if (lineSubtotal.compareTo(quantity.multiply(unitPriceAmount)) != 0) throw new SalesOrderInvariantViolation("Sales order line subtotal is inconsistent");
		catalogItemId = catalogItemId.trim(); itemNameSnapshot = itemNameSnapshot.trim(); presentationSnapshot = presentationSnapshot.trim(); unit = unit.trim();
		if (sellableSkuId != null && (productFamilyId == null || skuCodeSnapshot == null || skuCodeSnapshot.isBlank()
				|| productFamilyCodeSnapshot == null || productFamilyCodeSnapshot.isBlank())) {
			throw new SalesOrderInvariantViolation("Canonical SKU line snapshot is incomplete");
		}
		if (skuCodeSnapshot != null) skuCodeSnapshot = skuCodeSnapshot.trim();
		if (productFamilyCodeSnapshot != null) productFamilyCodeSnapshot = productFamilyCodeSnapshot.trim();
	}
}
