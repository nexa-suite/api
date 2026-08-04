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
            String brandId, String countryOfOrigin, String manufacturerReference, String supplierReference,
            String storageFamily, String status, long version, Instant createdAt, Instant updatedAt) { }
    public record SkuView(String id, String familyId, String familyName, String skuCode, String gtin,
            String presentation, String packagingType, String unitOfMeasure, BigDecimal netWeight,
            BigDecimal grossWeight, BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax,
            int shelfLifeDays, int minimumRemainingShelfLifeDays, boolean lotTrackingRequired,
            boolean expiryTrackingRequired, String taxCategory, String status, boolean visible, long version,
            PriceView currentPrice, Instant createdAt, Instant updatedAt) { }
    public record PriceView(String id, String skuId, BigDecimal amount, String currency, Instant validFrom,
            Instant validUntil, String sourceCode, String sourceDescription, long version, boolean cancelled) { }
}
