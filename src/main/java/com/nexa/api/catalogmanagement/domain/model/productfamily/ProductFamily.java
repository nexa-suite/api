package com.nexa.api.catalogmanagement.domain.model.productfamily;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Commercial concept shared by one or more sellable presentations. */
public final class ProductFamily {
    private final UUID id;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final String code;
    private String name;
    private String description;
    private final UUID categoryId;
    private final UUID brandId;
    private final String countryOfOrigin;
    private final String manufacturerReference;
    private final String supplierReference;
    private final String storageFamily;
    private ProductFamilyStatus status;
    private long version;

    private ProductFamily(UUID id, UUID tenantId, UUID workspaceId, String code, String name, String description,
            UUID categoryId, UUID brandId, String countryOfOrigin, String manufacturerReference,
            String supplierReference, String storageFamily, ProductFamilyStatus status, long version) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.code = required(code, "Family code", 80);
        this.name = required(name, "Family name", 200);
        this.description = Objects.requireNonNullElse(description, "");
        this.categoryId = Objects.requireNonNull(categoryId);
        this.brandId = Objects.requireNonNull(brandId);
        this.countryOfOrigin = normalizeCountry(countryOfOrigin);
        this.manufacturerReference = trim(manufacturerReference, 160);
        this.supplierReference = trim(supplierReference, 160);
        this.storageFamily = required(storageFamily, "Storage family", 16).toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("AMBIENT", "REFRIGERATED", "FROZEN").contains(this.storageFamily)) {
            throw new IllegalArgumentException("Unsupported storage family");
        }
        this.status = Objects.requireNonNull(status);
        if (version < 0) throw new IllegalArgumentException("Family version cannot be negative");
        this.version = version;
    }

    public static ProductFamily create(UUID tenantId, UUID workspaceId, String code, String name, String description,
            UUID categoryId, UUID brandId, String countryOfOrigin, String manufacturerReference,
            String supplierReference, String storageFamily, Instant now) {
        Objects.requireNonNull(now, "Creation time is required");
        return new ProductFamily(UUID.randomUUID(), tenantId, workspaceId, code, name, description, categoryId, brandId,
                countryOfOrigin, manufacturerReference, supplierReference, storageFamily, ProductFamilyStatus.DRAFT, 0);
    }

    public static ProductFamily restore(UUID id, UUID tenantId, UUID workspaceId, String code, String name,
            String description, UUID categoryId, UUID brandId, String countryOfOrigin, String manufacturerReference,
            String supplierReference, String storageFamily, ProductFamilyStatus status, long version) {
        return new ProductFamily(id, tenantId, workspaceId, code, name, description, categoryId, brandId,
                countryOfOrigin, manufacturerReference, supplierReference, storageFamily, status, version);
    }

    public void rename(String name, String description, long expectedVersion) {
        requireVersion(expectedVersion);
        this.name = required(name, "Family name", 200);
        this.description = Objects.requireNonNullElse(description, "");
        version++;
    }

    public void activate(long expectedVersion) { transition(ProductFamilyStatus.ACTIVE, expectedVersion); }
    public void deactivate(long expectedVersion) { transition(ProductFamilyStatus.INACTIVE, expectedVersion); }
    public void archive(long expectedVersion) { transition(ProductFamilyStatus.ARCHIVED, expectedVersion); }

    private void transition(ProductFamilyStatus next, long expectedVersion) {
        requireVersion(expectedVersion);
        if (status == ProductFamilyStatus.ARCHIVED && next != ProductFamilyStatus.ARCHIVED) {
            throw new IllegalStateException("Archived family cannot be reactivated");
        }
        status = next;
        version++;
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) throw new IllegalStateException("Product family version is stale");
    }

    private static String required(String value, String label, int max) {
        String normalized = Objects.requireNonNull(value, label + " is required").trim();
        if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }
    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw new IllegalArgumentException("Reference is too long");
        return value.trim();
    }
    private static String normalizeCountry(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}")) throw new IllegalArgumentException("Country of origin is invalid");
        return normalized;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID workspaceId() { return workspaceId; }
    public String code() { return code; }
    public String name() { return name; }
    public String description() { return description; }
    public UUID categoryId() { return categoryId; }
    public UUID brandId() { return brandId; }
    public String countryOfOrigin() { return countryOfOrigin; }
    public String manufacturerReference() { return manufacturerReference; }
    public String supplierReference() { return supplierReference; }
    public String storageFamily() { return storageFamily; }
    public ProductFamilyStatus status() { return status; }
    public long version() { return version; }
}
