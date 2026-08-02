package com.nexa.api.catalogmanagement.application;

/** Stable permission codes owned by the Catalog boundary. */
public final class CatalogPermissions {
    public static final String READ = "catalog:read";
    public static final String MANAGE = "catalog:manage";
    public static final String PRICE_MANAGE = "catalog:price:manage";
    public static final String PROMOTION_READ = "promotion:read";
    public static final String PROMOTION_MANAGE = "promotion:manage";

    private CatalogPermissions() { }
}
