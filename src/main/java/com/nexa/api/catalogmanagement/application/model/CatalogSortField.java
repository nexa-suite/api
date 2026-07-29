package com.nexa.api.catalogmanagement.application.model;

import java.util.Locale;

public enum CatalogSortField {
	ITEM_NAME("itemName"),
	BRAND_NAME("brandName"),
	CATEGORY_NAME("categoryName"),
	UNIT_PRICE("unitPrice");

	private final String wireValue;

	CatalogSortField(String wireValue) {
		this.wireValue = wireValue;
	}

	public String wireValue() {
		return wireValue;
	}

	public static CatalogSortField fromWireValue(String value) {
		if (value == null || value.isBlank()) return ITEM_NAME;
		String normalized = value.trim();
		for (CatalogSortField field : values()) {
			if (field.wireValue.equals(normalized) || field.name().equalsIgnoreCase(normalized)) return field;
		}
		throw new IllegalArgumentException("Unsupported catalog sort field: " + value.toLowerCase(Locale.ROOT));
	}
}
