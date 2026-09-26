package com.nexa.api.catalogcommercialpolicy.domain.model.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Resolves the BC-03 Base Price, permitted Customer Terms, Price List, and one Promotion. */
public final class AuthoritativePriceResolver {
    private final EffectivePricePolicy promotionPolicy = new EffectivePricePolicy();

    public ResolvedOffer resolve(BigDecimal baseAmount, String baseCurrency, PriceListPrice priceListPrice,
                                 CustomerTerms customerTerms, UUID customerAccountId,
                                 String clientSegment, String buyerTier, BigDecimal quantity,
                                 List<PromotionCandidate> promotions, Instant effectiveAt) {
        BigDecimal selectedAmount = Objects.requireNonNull(baseAmount, "Base Price is required");
        String selectedCurrency = normalizeCurrency(baseCurrency);
        if (customerTerms != null) {
            if (!selectedCurrency.equals(normalizeCurrency(customerTerms.currency()))) {
                throw new IllegalStateException("Customer Terms currency does not match the resolved price currency");
            }
            if (customerTerms.priceListId() != null && priceListPrice != null
                    && customerTerms.priceListId().equals(priceListPrice.priceListId())) {
                if (!selectedCurrency.equals(normalizeCurrency(priceListPrice.currency()))) {
                    throw new IllegalStateException("Price List currency does not match Customer Terms currency");
                }
                selectedAmount = priceListPrice.unitPrice();
            }
        }

        EffectivePricePolicy.Result result = promotionPolicy.calculate(selectedAmount, selectedCurrency,
                quantity, customerAccountId, clientSegment, buyerTier, promotions, effectiveAt);
        return new ResolvedOffer(result.basePrice(), result.effectivePrice(), result.discountAmount(),
                selectedCurrency, result.appliedPromotions(), effectiveAt);
    }

    private static String normalizeCurrency(String currency) {
        String normalized = Objects.requireNonNull(currency, "Price currency is required").strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw new IllegalStateException("Price currency is invalid");
        return normalized;
    }

    public record PriceListPrice(UUID priceListId, BigDecimal unitPrice, String currency) {
        public PriceListPrice {
            Objects.requireNonNull(priceListId, "Price List id is required");
            Objects.requireNonNull(unitPrice, "Price List unit price is required");
            if (unitPrice.signum() < 0) throw new IllegalArgumentException("Price List unit price cannot be negative");
        }
    }

    public record CustomerTerms(UUID priceListId, String currency) {
        public CustomerTerms {
            Objects.requireNonNull(currency, "Customer Terms currency is required");
        }
    }

    public record ResolvedOffer(BigDecimal basePrice, BigDecimal effectivePrice, BigDecimal discountAmount,
                                String currency, List<EffectivePricePolicy.AppliedPromotion> appliedPromotions,
                                Instant effectiveAt) {
        public ResolvedOffer {
            Objects.requireNonNull(basePrice);
            Objects.requireNonNull(effectivePrice);
            Objects.requireNonNull(discountAmount);
            Objects.requireNonNull(currency);
            appliedPromotions = List.copyOf(appliedPromotions);
            Objects.requireNonNull(effectiveAt);
        }
    }
}
