package com.nexa.api.catalogmanagement.domain.model.category;

import java.util.Objects;

public final class Category {
    private final CategoryId id;
    private final CategoryId parentId;
    private String slug;
    private String name;
    private String description;
    private CategoryStatus status;

    private Category(CategoryId id, CategoryId parentId, String slug, String name, String description) {
        this.id = Objects.requireNonNull(id, "Category id is required");
        this.parentId = parentId;
        this.slug = bounded(slug, "Category slug", 100);
        this.name = bounded(name, "Category name", 160);
        this.description = description == null ? null : bounded(description, "Category description", 2000);
        if (parentId != null && parentId.equals(id)) throw new IllegalArgumentException("Category cannot be its own parent");
        this.status = CategoryStatus.DRAFT;
    }

    public static Category create(CategoryId id, CategoryId parentId, String slug, String name, String description) {
        return new Category(id, parentId, slug, name, description);
    }

    public static Category restore(CategoryId id, CategoryId parentId, String slug, String name, String description, CategoryStatus status) {
        Category category = new Category(id, parentId, slug, name, description);
        category.status = Objects.requireNonNull(status, "Category status is required");
        return category;
    }

    public CategoryId id() { return id; }
    public CategoryId parentId() { return parentId; }
    public String slug() { return slug; }
    public String name() { return name; }
    public String description() { return description; }
    public CategoryStatus status() { return status; }
    public void activate() {
        if (status == CategoryStatus.ARCHIVED) throw new IllegalStateException("Archived category cannot be activated");
        status = CategoryStatus.ACTIVE;
    }
    public void deactivate() {
        if (status == CategoryStatus.ARCHIVED) throw new IllegalStateException("Archived category cannot be deactivated");
        status = CategoryStatus.INACTIVE;
    }
    public void archive() {
        if (status == CategoryStatus.ACTIVE) throw new IllegalStateException("Active category cannot be archived");
        if (status == CategoryStatus.ARCHIVED) throw new IllegalStateException("Archived category cannot be archived");
        status = CategoryStatus.ARCHIVED;
    }
    public void rename(String value) { name = bounded(value, "Category name", 160); }
    public void changeSlug(String value) { slug = bounded(value, "Category slug", 100); }
    public void rewriteDescription(String value) { description = value == null ? null : bounded(value, "Category description", 2000); }

    private static String bounded(String value, String label, int max) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }
}
