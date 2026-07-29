package com.nexa.api.catalogmanagement.application.exception;

public final class CatalogItemNotFoundException extends RuntimeException {
	public CatalogItemNotFoundException(String catalogItemId) {
		super("Catalog item was not found: " + catalogItemId);
	}
}
