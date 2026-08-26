package com.nexa.api.catalogcommercialpolicy.application.port.out;

public interface CatalogAuthorizationPort {
	void requireCatalogRead();
	default void require(String permission) { requireCatalogRead(); }
}
