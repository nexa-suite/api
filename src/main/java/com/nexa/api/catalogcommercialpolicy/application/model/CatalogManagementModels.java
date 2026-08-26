package com.nexa.api.catalogcommercialpolicy.application.model;

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
            Instant validUntil, String sourceCode, String sourceDescription, boolean cancelled, long version) {
        public PriceView {
            if (amount != null && amount.signum() < 0) throw new IllegalArgumentException("Price amount cannot be negative");
            if (amount != null && amount.scale() > 2) throw new IllegalArgumentException("Price amount cannot have more than two decimals");
        }
    }
    public record PromotionView(String id, String slug, String name, String description, String status,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, int priority, List<String> productIds, List<String> categoryIds,
            List<String> clientAccountIds, List<PromotionRuleView> rules, long version) {
        public PromotionView {
            if (priority < -1_000_000 || priority > 1_000_000) throw new IllegalArgumentException("Promotion priority is invalid");
            productIds = List.copyOf(productIds == null ? List.of() : productIds);
            categoryIds = List.copyOf(categoryIds == null ? List.of() : categoryIds);
            clientAccountIds = List.copyOf(clientAccountIds == null ? List.of() : clientAccountIds);
            rules = List.copyOf(rules == null ? List.of() : rules);
        }

        public PromotionView(String id, String slug, String name, String description, String status,
                String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
                BigDecimal minimumQuantity, String stackingPolicy, List<String> productIds, List<String> categoryIds,
                List<String> clientAccountIds, List<PromotionRuleView> rules, long version) {
            this(id, slug, name, description, status, discountType, discountValue, currency, startsAt, endsAt,
                    minimumQuantity, stackingPolicy, 0, productIds, categoryIds, clientAccountIds, rules, version);
        }

        public PromotionView(String id, String slug, String name, String description, String status,
                String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
                BigDecimal minimumQuantity, String stackingPolicy, List<String> productIds, List<String> categoryIds,
                long version) {
            this(id, slug, name, description, status, discountType, discountValue, currency, startsAt, endsAt,
                    minimumQuantity, stackingPolicy, 0, productIds, categoryIds, List.of(), List.of(), version);
        }

        public PromotionView(String id, String slug, String name, String description, String status,
                String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
                BigDecimal minimumQuantity, String stackingPolicy, int priority, List<String> productIds, List<String> categoryIds,
                long version) {
            this(id, slug, name, description, status, discountType, discountValue, currency, startsAt, endsAt,
                    minimumQuantity, stackingPolicy, priority, productIds, categoryIds, List.of(), List.of(), version);
        }
    }
    public record PromotionRuleView(String type, String value) { }
}
