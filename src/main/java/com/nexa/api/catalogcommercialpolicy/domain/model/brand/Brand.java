package com.nexa.api.catalogcommercialpolicy.domain.model.brand;

import java.util.Objects;

public final class Brand {
    private final BrandId id;
    private String slug;
    private String name;
    private String description;
    private BrandStatus status;

    private Brand(BrandId id, String slug, String name, String description) {
        this.id = Objects.requireNonNull(id, "Brand id is required");
        this.slug = bounded(slug, "Brand slug", 100);
        this.name = bounded(name, "Brand name", 160);
        this.description = description == null ? null : bounded(description, "Brand description", 2000);
        this.status = BrandStatus.DRAFT;
    }

    public static Brand create(BrandId id, String slug, String name, String description) {
        return new Brand(id, slug, name, description);
    }
    public static Brand restore(BrandId id, String slug, String name, String description, BrandStatus status) {
        Brand brand = new Brand(id, slug, name, description);
        brand.status = Objects.requireNonNull(status, "Brand status is required");
        return brand;
    }
    public BrandId id() { return id; }
    public String slug() { return slug; }
    public String name() { return name; }
    public String description() { return description; }
    public BrandStatus status() { return status; }
    public void activate() {
        if (status == BrandStatus.ARCHIVED) throw new IllegalStateException("Archived brand cannot be activated");
        status = BrandStatus.ACTIVE;
    }
    public void deactivate() {
        if (status == BrandStatus.ARCHIVED) throw new IllegalStateException("Archived brand cannot be deactivated");
        status = BrandStatus.INACTIVE;
    }
    public void archive() {
        if (status == BrandStatus.ACTIVE) throw new IllegalStateException("Active brand cannot be archived");
        if (status == BrandStatus.ARCHIVED) throw new IllegalStateException("Archived brand cannot be archived");
        status = BrandStatus.ARCHIVED;
    }
    public void rename(String value) { name = bounded(value, "Brand name", 160); }
    public void changeSlug(String value) { slug = bounded(value, "Brand slug", 100); }
    public void rewriteDescription(String value) { description = value == null ? null : bounded(value, "Brand description", 2000); }

    private static String bounded(String value, String label, int max) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }
}
