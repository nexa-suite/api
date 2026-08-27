package com.nexa.api.catalogcommercialpolicy.application.exception;

public final class CatalogItemNotFoundException extends RuntimeException {
	public CatalogItemNotFoundException(String catalogItemId) {
		super("Catalog item was not found: " + catalogItemId);
	}
}
