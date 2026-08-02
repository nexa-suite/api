package com.nexa.api.catalogmanagement.domain.model.brand;

import java.util.Objects;

public final class Brand {
    private final BrandId id;
    private String slug;
    private String name;
    private String description;

    private Brand(BrandId id, String slug, String name, String description) {
        this.id = Objects.requireNonNull(id);
        this.slug = bounded(slug, "Brand slug", 100);
        this.name = bounded(name, "Brand name", 160);
        this.description = description == null ? null : bounded(description, "Brand description", 2000);
    }

    public static Brand create(BrandId id, String slug, String name, String description) {
        return new Brand(id, slug, name, description);
    }
    public BrandId id() { return id; }
    public String slug() { return slug; }
    public String name() { return name; }
    public String description() { return description; }
    public void rename(String value) { name = bounded(value, "Brand name", 160); }
    public void changeSlug(String value) { slug = bounded(value, "Brand slug", 100); }

    private static String bounded(String value, String label, int max) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }
}
