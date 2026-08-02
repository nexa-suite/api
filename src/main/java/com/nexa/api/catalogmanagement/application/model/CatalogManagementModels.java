package com.nexa.api.catalogmanagement.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CatalogManagementModels {
    private CatalogManagementModels() { }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items); }
        public int totalPages() { return total == 0 ? 0 : (int) Math.ceil((double) total / size); }
    }
    public record CategoryView(String id, String slug, String name, String description, String parentId, String status, long version) { }
    public record BrandView(String id, String slug, String name, String description, String status, long version) { }
    public record ProductView(String id, String catalogItemId, String productCode, String slug, String name,
            String description, String categoryId, String categoryName, String brandId, String brandName,
            String storageTemperature, String status, String presentation, String unitOfMeasure,
            boolean buyerVisible, String imagePath, PriceView currentPrice, long version) { }
    public record PriceView(String id, String productId, BigDecimal amount, String currency, Instant validFrom,
            Instant validUntil, String sourceCode, String sourceDescription, boolean cancelled, long version) { }
    public record PromotionView(String id, String slug, String name, String description, String status,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<String> productIds, List<String> categoryIds,
            long version) {
        public PromotionView { productIds = List.copyOf(productIds); categoryIds = List.copyOf(categoryIds); }
    }
}
