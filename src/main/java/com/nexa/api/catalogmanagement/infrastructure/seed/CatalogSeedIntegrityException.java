package com.nexa.api.catalogmanagement.infrastructure.seed;

public final class CatalogSeedIntegrityException extends RuntimeException {
	public CatalogSeedIntegrityException(String reason) {
		super("Catalog seed integrity failure: " + reason);
	}
}
