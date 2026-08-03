package com.nexa.api.catalogmanagement.application.model;

import com.nexa.api.catalogmanagement.domain.model.pricing.PromotionCandidate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CatalogPricingPreviewModels {
    private CatalogPricingPreviewModels() { }

    public record Request(List<ItemRequest> items, Instant asOf) {
        public Request {
            items = items == null ? List.of() : List.copyOf(items);
            if (items.isEmpty() || items.size() > 100) throw new IllegalArgumentException("Pricing preview requires between 1 and 100 items");
            if (items.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Pricing preview items are invalid");
            if (items.stream().map(ItemRequest::productId).distinct().count() != items.size()) {
                throw new IllegalArgumentException("Pricing preview cannot contain duplicate products");
            }
        }
    }

    public record ItemRequest(UUID productId, BigDecimal quantity) {
        public ItemRequest {
            Objects.requireNonNull(productId, "Product id is required");
            if (quantity == null || quantity.signum() <= 0 || quantity.scale() > 4) {
                throw new IllegalArgumentException("Pricing preview quantity is invalid");
            }
        }
    }

    public record Result(List<ItemResult> items) {
        public Result { items = List.copyOf(items); }
    }

    public record ItemResult(UUID productId, BigDecimal quantity, BigDecimal baseUnitPrice,
            BigDecimal effectiveUnitPrice, BigDecimal lineBaseTotal, BigDecimal lineEffectiveTotal,
            BigDecimal discountAmount, String currency, List<AppliedPromotion> appliedPromotions, Instant pricingAsOf) {
        public ItemResult {
            Objects.requireNonNull(productId, "Product id is required");
            Objects.requireNonNull(quantity, "Quantity is required");
            Objects.requireNonNull(baseUnitPrice, "Base price is required");
            Objects.requireNonNull(effectiveUnitPrice, "Effective price is required");
            Objects.requireNonNull(lineBaseTotal, "Base total is required");
            Objects.requireNonNull(lineEffectiveTotal, "Effective total is required");
            Objects.requireNonNull(discountAmount, "Discount amount is required");
            Objects.requireNonNull(currency, "Currency is required");
            appliedPromotions = List.copyOf(appliedPromotions);
        }
    }

    public record AppliedPromotion(UUID id, String name, String discountType, BigDecimal discountAmount) { }

    public record PriceContext(UUID productId, BigDecimal basePrice, String currency,
            List<PromotionCandidate> promotions) {
        public PriceContext { promotions = promotions == null ? List.of() : List.copyOf(promotions); }
    }
}
