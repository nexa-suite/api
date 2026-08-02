package com.nexa.api.catalogmanagement.domain.model.product;

import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemStatus;

import java.util.Objects;
import java.util.UUID;

public final class Product {
    private final UUID id;
    private final String catalogItemId;
    private final String productCode;
    private String slug;
    private String name;
    private String description;
    private CatalogItemStatus status;

    private Product(UUID id, String catalogItemId, String productCode, String slug, String name, String description) {
        this.id = Objects.requireNonNull(id);
        this.catalogItemId = bounded(catalogItemId, "Catalog item id", 64);
        this.productCode = bounded(productCode, "Product code", 64);
        this.slug = bounded(slug, "Product slug", 140);
        this.name = bounded(name, "Product name", 200);
        this.description = bounded(description, "Product description", 4000);
        this.status = CatalogItemStatus.DRAFT;
    }

    public static Product create(UUID id, String catalogItemId, String productCode, String slug, String name, String description) {
        return new Product(id, catalogItemId, productCode, slug, name, description);
    }
    public static Product restore(UUID id, String catalogItemId, String productCode, String slug, String name, String description, CatalogItemStatus status) {
        Product product = new Product(id, catalogItemId, productCode, slug, name, description);
        product.status = Objects.requireNonNull(status, "Product status is required");
        return product;
    }
    public UUID id() { return id; }
    public String catalogItemId() { return catalogItemId; }
    public String productCode() { return productCode; }
    public String slug() { return slug; }
    public String name() { return name; }
    public String description() { return description; }
    public CatalogItemStatus status() { return status; }
    public void activate() {
        if (status != CatalogItemStatus.DRAFT && status != CatalogItemStatus.INACTIVE && status != CatalogItemStatus.ACTIVE) {
            throw new IllegalStateException("Archived product cannot be activated");
        }
        status = CatalogItemStatus.ACTIVE;
    }
    public void deactivate() {
        if (status != CatalogItemStatus.DRAFT && status != CatalogItemStatus.ACTIVE && status != CatalogItemStatus.INACTIVE) {
            throw new IllegalStateException("Archived product cannot be deactivated");
        }
        status = CatalogItemStatus.INACTIVE;
    }
    public void discontinue() {
        if (status == CatalogItemStatus.ARCHIVED) throw new IllegalStateException("Archived product cannot be discontinued");
        status = CatalogItemStatus.DISCONTINUED;
    }
    public void archive() {
        if (status == CatalogItemStatus.ACTIVE || status == CatalogItemStatus.ARCHIVED) {
            throw new IllegalStateException(status == CatalogItemStatus.ACTIVE
                    ? "Active product cannot be archived" : "Archived product cannot be archived");
        }
        status = CatalogItemStatus.ARCHIVED;
    }
    public void rename(String value) { name = bounded(value, "Product name", 200); }
    public void changeSlug(String value) { slug = bounded(value, "Product slug", 140); }
    public void rewriteDescription(String value) { description = bounded(value, "Product description", 4000); }

    private static String bounded(String value, String label, int max) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }
}
