package com.nexa.api.catalogcommercialpolicy.application.model;

public enum SortDirection {
	ASC("asc"),
	DESC("desc");

	private final String wireValue;

	SortDirection(String wireValue) {
		this.wireValue = wireValue;
	}

	public String wireValue() {
		return wireValue;
	}

	public static SortDirection fromWireValue(String value) {
		if (value == null || value.isBlank()) return ASC;
		return switch (value.trim().toLowerCase()) {
			case "asc" -> ASC;
			case "desc" -> DESC;
			default -> throw new IllegalArgumentException("Unsupported catalog sort direction: " + value);
		};
	}
}
