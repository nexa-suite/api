package com.nexa.api.catalogmanagement.domain.model.pricing;

import com.nexa.api.catalogmanagement.domain.model.promotion.Promotion;
import com.nexa.api.catalogmanagement.domain.model.promotion.PromotionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EffectivePricePolicyTests {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void appliesTheBestExclusivePromotionAndNeverCreatesNegativePrice() {
        PromotionCandidate tenPercent = candidate("10", Promotion.DiscountType.PERCENTAGE, "10", null, Promotion.StackingPolicy.EXCLUSIVE, "PERCENTAGE");
        PromotionCandidate fixedMore = candidate("20", Promotion.DiscountType.FIXED_AMOUNT, "120", "PEN", Promotion.StackingPolicy.EXCLUSIVE, "FIXED_AMOUNT");

        EffectivePricePolicy.Result result = new EffectivePricePolicy().calculate(new BigDecimal("100.00"), "PEN", BigDecimal.ONE,
                List.of(tenPercent, fixedMore), NOW);

        assertThat(result.effectivePrice()).isEqualByComparingTo("0");
        assertThat(result.discountAmount()).isEqualByComparingTo("100");
        assertThat(result.appliedPromotions()).extracting(EffectivePricePolicy.AppliedPromotion::id)
                .containsExactly(fixedMore.id());
    }

    @Test
    void rejectsMinimumQuantityAndCurrencyMismatchServerSide() {
        PromotionCandidate quantity = candidate("30", Promotion.DiscountType.PERCENTAGE, "20", null, Promotion.StackingPolicy.STACKABLE, "PERCENTAGE");
        quantity = new PromotionCandidate(quantity.id(), quantity.name(), quantity.discountType(), quantity.discountValue(), quantity.currency(),
                quantity.startsAt(), quantity.endsAt(), new BigDecimal("2"), quantity.stackingPolicy(), quantity.status());
        PromotionCandidate wrongCurrency = candidate("31", Promotion.DiscountType.FIXED_AMOUNT, "5", "USD", Promotion.StackingPolicy.STACKABLE, "FIXED_AMOUNT");

        EffectivePricePolicy.Result result = new EffectivePricePolicy().calculate(new BigDecimal("100"), "PEN", BigDecimal.ONE,
                List.of(quantity, wrongCurrency), NOW);

        assertThat(result.effectivePrice()).isEqualByComparingTo("100");
        assertThat(result.appliedPromotions()).isEmpty();
    }

    private static PromotionCandidate candidate(String id, Promotion.DiscountType type, String value, String currency,
            Promotion.StackingPolicy stacking, String ignored) {
        return new PromotionCandidate(UUID.fromString("00000000-0000-0000-0000-0000000000" + id), "Promotion " + id,
                type, new BigDecimal(value), currency, NOW.minusSeconds(60), NOW.plusSeconds(60), BigDecimal.ONE, stacking, PromotionStatus.ACTIVE);
    }
}
