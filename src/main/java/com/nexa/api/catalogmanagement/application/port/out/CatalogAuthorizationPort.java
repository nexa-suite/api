package com.nexa.api.catalogmanagement.application.port.out;

public interface CatalogAuthorizationPort {
	void requireCatalogRead();
	default void require(String permission) { requireCatalogRead(); }
}
