package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

public final class CatalogSeedIntegrityException extends RuntimeException {
	public CatalogSeedIntegrityException(String reason) {
		super("Catalog seed integrity failure: " + reason);
	}
}
