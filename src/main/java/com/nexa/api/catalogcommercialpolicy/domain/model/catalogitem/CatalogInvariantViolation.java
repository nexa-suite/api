package com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem;

public final class CatalogInvariantViolation extends RuntimeException {
	public CatalogInvariantViolation(String reason) {
		super(reason);
	}
}
