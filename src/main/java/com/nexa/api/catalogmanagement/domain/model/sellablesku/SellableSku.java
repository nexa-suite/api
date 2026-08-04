package com.nexa.api.catalogmanagement.domain.model.sellablesku;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Actual sellable presentation. Inventory, prices and sales lines reference it. */
public final class SellableSku {
    private final UUID id;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID familyId;
    private final String skuCode;
    private final String gtin;
    private final String presentation;
    private final String packagingType;
    private final String unitOfMeasure;
    private final BigDecimal netWeight;
    private final BigDecimal grossWeight;
    private final BigDecimal packQuantity;
    private final BigDecimal temperatureMin;
    private final BigDecimal temperatureMax;
    private final int shelfLifeDays;
    private final int minimumRemainingShelfLifeDays;
    private final boolean lotTrackingRequired;
    private final boolean expiryTrackingRequired;
    private final String taxCategory;
    private SellableSkuStatus status;
    private boolean visible;
    private long version;

    private SellableSku(UUID id, UUID tenantId, UUID workspaceId, UUID familyId, String skuCode, String gtin,
            String presentation, String packagingType, String unitOfMeasure, BigDecimal netWeight,
            BigDecimal grossWeight, BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax,
            int shelfLifeDays, int minimumRemainingShelfLifeDays, boolean lotTrackingRequired,
            boolean expiryTrackingRequired, String taxCategory, SellableSkuStatus status, boolean visible, long version) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.familyId = Objects.requireNonNull(familyId);
        this.skuCode = required(skuCode, "SKU code", 80);
        this.gtin = optionalDigits(gtin);
        this.presentation = required(presentation, "Presentation", 160);
        this.packagingType = required(packagingType, "Packaging type", 64);
        this.unitOfMeasure = required(unitOfMeasure, "Unit of measure", 32);
        this.netWeight = positiveOrNull(netWeight, "Net weight");
        this.grossWeight = positiveOrNull(grossWeight, "Gross weight");
        this.packQuantity = positive(packQuantity, "Pack quantity");
        if (temperatureMin != null && temperatureMax != null && temperatureMin.compareTo(temperatureMax) > 0) {
            throw new IllegalArgumentException("Temperature range is invalid");
        }
        this.temperatureMin = temperatureMin;
        this.temperatureMax = temperatureMax;
        if (shelfLifeDays < 0 || minimumRemainingShelfLifeDays < 0) throw new IllegalArgumentException("Shelf life is invalid");
        this.shelfLifeDays = shelfLifeDays;
        this.minimumRemainingShelfLifeDays = minimumRemainingShelfLifeDays;
        this.lotTrackingRequired = lotTrackingRequired;
        this.expiryTrackingRequired = expiryTrackingRequired;
        this.taxCategory = required(taxCategory, "Tax category", 64);
        this.status = Objects.requireNonNull(status);
        this.visible = visible;
        if (version < 0) throw new IllegalArgumentException("SKU version cannot be negative");
        this.version = version;
    }

    public static SellableSku create(UUID tenantId, UUID workspaceId, UUID familyId, String skuCode, String gtin,
            String presentation, String packagingType, String unitOfMeasure, BigDecimal netWeight,
            BigDecimal grossWeight, BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax,
            int shelfLifeDays, int minimumRemainingShelfLifeDays, boolean lotTrackingRequired,
            boolean expiryTrackingRequired, String taxCategory, Instant now) {
        Objects.requireNonNull(now, "Creation time is required");
        return new SellableSku(UUID.randomUUID(), tenantId, workspaceId, familyId, skuCode, gtin, presentation,
                packagingType, unitOfMeasure, netWeight, grossWeight, packQuantity, temperatureMin, temperatureMax,
                shelfLifeDays, minimumRemainingShelfLifeDays, lotTrackingRequired, expiryTrackingRequired, taxCategory,
                SellableSkuStatus.DRAFT, true, 0);
    }

    public static SellableSku restore(UUID id, UUID tenantId, UUID workspaceId, UUID familyId, String skuCode, String gtin,
            String presentation, String packagingType, String unitOfMeasure, BigDecimal netWeight,
            BigDecimal grossWeight, BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax,
            int shelfLifeDays, int minimumRemainingShelfLifeDays, boolean lotTrackingRequired,
            boolean expiryTrackingRequired, String taxCategory, SellableSkuStatus status, boolean visible, long version) {
        return new SellableSku(id, tenantId, workspaceId, familyId, skuCode, gtin, presentation, packagingType,
                unitOfMeasure, netWeight, grossWeight, packQuantity, temperatureMin, temperatureMax, shelfLifeDays,
                minimumRemainingShelfLifeDays, lotTrackingRequired, expiryTrackingRequired, taxCategory, status, visible, version);
    }

    public void activate(long expectedVersion) { transition(SellableSkuStatus.ACTIVE, expectedVersion); }
    public void deactivate(long expectedVersion) { transition(SellableSkuStatus.INACTIVE, expectedVersion); }
    public void discontinue(long expectedVersion) { transition(SellableSkuStatus.DISCONTINUED, expectedVersion); }
    public void archive(long expectedVersion) { transition(SellableSkuStatus.ARCHIVED, expectedVersion); }
    private void transition(SellableSkuStatus next, long expectedVersion) {
        if (expectedVersion != version) throw new IllegalStateException("SKU version is stale");
        if (status == SellableSkuStatus.ARCHIVED && next != SellableSkuStatus.ARCHIVED) throw new IllegalStateException("Archived SKU cannot be reactivated");
        status = next;
        visible = next == SellableSkuStatus.ACTIVE;
        version++;
    }

    private static String required(String value, String label, int max) {
        String normalized = Objects.requireNonNull(value, label + " is required").trim();
        if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }
    private static String optionalDigits(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!normalized.matches("[0-9]{8,14}")) throw new IllegalArgumentException("GTIN is invalid");
        return normalized;
    }
    private static BigDecimal positiveOrNull(BigDecimal value, String label) {
        if (value == null) return null;
        return positive(value, label);
    }
    private static BigDecimal positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID workspaceId() { return workspaceId; }
    public UUID familyId() { return familyId; }
    public String skuCode() { return skuCode; }
    public String gtin() { return gtin; }
    public String presentation() { return presentation; }
    public String packagingType() { return packagingType; }
    public String unitOfMeasure() { return unitOfMeasure; }
    public BigDecimal netWeight() { return netWeight; }
    public BigDecimal grossWeight() { return grossWeight; }
    public BigDecimal packQuantity() { return packQuantity; }
    public BigDecimal temperatureMin() { return temperatureMin; }
    public BigDecimal temperatureMax() { return temperatureMax; }
    public int shelfLifeDays() { return shelfLifeDays; }
    public int minimumRemainingShelfLifeDays() { return minimumRemainingShelfLifeDays; }
    public boolean lotTrackingRequired() { return lotTrackingRequired; }
    public boolean expiryTrackingRequired() { return expiryTrackingRequired; }
    public String taxCategory() { return taxCategory; }
    public SellableSkuStatus status() { return status; }
    public boolean visible() { return visible; }
    public long version() { return version; }
}
