package com.nexa.api.catalogmanagement.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CatalogSkuModels {
    private CatalogSkuModels() { }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items); }
    }
    public record FamilyView(String id, String code, String name, String description, String categoryId,
            String categoryName, String brandId, String brandName, String countryOfOrigin,
            String manufacturerReference, String supplierReference, String storageFamily, String status,
            long skuCount, String imagePath, String imageFileName, long version, Instant createdAt,
            Instant updatedAt) { }
    public record SkuView(String id, String familyId, String familyCode, String familyName, String categoryName,
            String brandName, String skuCode, String gtin, String presentation, String packagingType,
            String unitOfMeasure, BigDecimal netWeight, BigDecimal grossWeight, BigDecimal packQuantity,
            BigDecimal temperatureMin, BigDecimal temperatureMax, int shelfLifeDays,
            int minimumRemainingShelfLifeDays, boolean lotTrackingRequired, boolean expiryTrackingRequired,
            String taxCategory, String status, boolean visible, long version, String legacyCatalogItemId,
            String imagePath, String imageFileName, String availabilityStatus, boolean nearExpiry,
            Instant availabilityAsOf, PriceView currentPrice, Instant createdAt, Instant updatedAt,
            String variantId, String variantCode, String variantName) {
        public SkuView(String id, String familyId, String familyCode, String familyName, String categoryName,
                String brandName, String skuCode, String gtin, String presentation, String packagingType,
                String unitOfMeasure, BigDecimal netWeight, BigDecimal grossWeight, BigDecimal packQuantity,
                BigDecimal temperatureMin, BigDecimal temperatureMax, int shelfLifeDays,
                int minimumRemainingShelfLifeDays, boolean lotTrackingRequired, boolean expiryTrackingRequired,
                String taxCategory, String status, boolean visible, long version, String legacyCatalogItemId,
                String imagePath, String imageFileName, String availabilityStatus, boolean nearExpiry,
                Instant availabilityAsOf, PriceView currentPrice, Instant createdAt, Instant updatedAt) {
            this(id, familyId, familyCode, familyName, categoryName, brandName, skuCode, gtin, presentation,
                    packagingType, unitOfMeasure, netWeight, grossWeight, packQuantity, temperatureMin,
                    temperatureMax, shelfLifeDays, minimumRemainingShelfLifeDays, lotTrackingRequired,
                    expiryTrackingRequired, taxCategory, status, visible, version, legacyCatalogItemId,
                    imagePath, imageFileName, availabilityStatus, nearExpiry, availabilityAsOf, currentPrice,
                    createdAt, updatedAt, null, null, null);
        }

        public SkuView withAvailability(String status, boolean nearExpiry, Instant asOf) {
            return new SkuView(id, familyId, familyCode, familyName, categoryName, brandName, skuCode, gtin,
                    presentation, packagingType, unitOfMeasure, netWeight, grossWeight, packQuantity,
                    temperatureMin, temperatureMax, shelfLifeDays, minimumRemainingShelfLifeDays,
                    lotTrackingRequired, expiryTrackingRequired, taxCategory, this.status, visible, version,
                    legacyCatalogItemId, imagePath, imageFileName, status, nearExpiry, asOf, currentPrice,
                    createdAt, updatedAt, variantId, variantCode, variantName);
        }
    }
    public record PriceView(String id, String skuId, BigDecimal amount, String currency, Instant validFrom,
            Instant validUntil, String sourceCode, String sourceDescription, long version, boolean cancelled) { }
}
