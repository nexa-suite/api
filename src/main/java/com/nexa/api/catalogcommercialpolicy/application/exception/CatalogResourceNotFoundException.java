package com.nexa.api.catalogcommercialpolicy.application.exception;

public final class CatalogResourceNotFoundException extends RuntimeException {
    private final String resource;
    public CatalogResourceNotFoundException(String resource) { super(resource); this.resource = resource; }
    public String resource() { return resource; }
}
