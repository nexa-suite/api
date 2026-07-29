package com.nexa.api.catalogmanagement.domain.model.catalogitem;

public final class CatalogInvariantViolation extends RuntimeException {
	public CatalogInvariantViolation(String reason) {
		super(reason);
	}
}
