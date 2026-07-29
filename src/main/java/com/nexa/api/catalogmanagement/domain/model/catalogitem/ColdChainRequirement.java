package com.nexa.api.catalogmanagement.domain.model.catalogitem;

import java.util.Locale;

public enum ColdChainRequirement {
	NONE,
	REFRIGERATED,
	FROZEN;

	public static ColdChainRequirement fromLegacyValue(String value) {
		if (value == null || value.isBlank()) throw new CatalogInvariantViolation("Cold-chain requirement is required");
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "NONE" -> NONE;
			case "REFRIGERATED" -> REFRIGERATED;
			case "FROZEN" -> FROZEN;
			default -> throw new CatalogInvariantViolation("Unknown cold-chain requirement");
		};
	}
}
