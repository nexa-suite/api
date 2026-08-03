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

    @Test
    void calculatesCanonicalQuantityPreviewAt390Pen() {
        PromotionCandidate tenPercent = candidate("40", Promotion.DiscountType.PERCENTAGE, "10", null,
                Promotion.StackingPolicy.EXCLUSIVE, "TEN-PERCENT", 0, BigDecimal.ONE, PromotionStatus.ACTIVE);

        EffectivePricePolicy.Result result = new EffectivePricePolicy().calculate(new BigDecimal("390.00"), "PEN",
                new BigDecimal("5"), List.of(tenPercent), NOW);

        assertThat(result.basePrice()).isEqualByComparingTo("390");
        assertThat(result.effectivePrice()).isEqualByComparingTo("351");
        assertThat(result.discountAmount()).isEqualByComparingTo("39");
        assertThat(result.totalEffectivePrice(new BigDecimal("5"))).isEqualByComparingTo("1755");
        assertThat(result.appliedPromotions()).extracting(EffectivePricePolicy.AppliedPromotion::id)
                .containsExactly(tenPercent.id());
    }

    @Test
    void appliesMinimumQuantityAndClientEligibility() {
        UUID clientId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        PromotionCandidate targeted = new PromotionCandidate(clientId, "Targeted", "TARGETED", Promotion.DiscountType.PERCENTAGE,
                new BigDecimal("20"), null, NOW.minusSeconds(60), NOW.plusSeconds(60), new BigDecimal("2"),
                Promotion.StackingPolicy.EXCLUSIVE, PromotionStatus.ACTIVE, 5, List.of(clientId),
                List.of(new PromotionCandidate.PromotionRule("CLIENT_SEGMENT", "HOSPITAL"),
                        new PromotionCandidate.PromotionRule("BUYER_TIER", "GOLD")));
        EffectivePricePolicy policy = new EffectivePricePolicy();

        assertThat(policy.calculate(new BigDecimal("100"), "PEN", BigDecimal.ONE, clientId, "HOSPITAL", "GOLD",
                List.of(targeted), NOW).appliedPromotions()).isEmpty();
        assertThat(policy.calculate(new BigDecimal("100"), "PEN", new BigDecimal("2"), clientId, "HOSPITAL", "GOLD",
                List.of(targeted), NOW).effectivePrice()).isEqualByComparingTo("80");
        assertThat(policy.calculate(new BigDecimal("100"), "PEN", new BigDecimal("2"), clientId, "RETAIL", "GOLD",
                List.of(targeted), NOW).appliedPromotions()).isEmpty();
    }

    @Test
    void comparesExclusiveAgainstCombinedStackableAndUsesFixedOrder() {
        PromotionCandidate exclusive = candidate("41", Promotion.DiscountType.PERCENTAGE, "30", null,
                Promotion.StackingPolicy.EXCLUSIVE, "EXCLUSIVE", 0, BigDecimal.ONE, PromotionStatus.ACTIVE);
        PromotionCandidate fixed = candidate("42", Promotion.DiscountType.FIXED_AMOUNT, "15", "PEN",
                Promotion.StackingPolicy.STACKABLE, "FIXED", 1, BigDecimal.ONE, PromotionStatus.ACTIVE);
        PromotionCandidate percentage = candidate("43", Promotion.DiscountType.PERCENTAGE, "20", null,
                Promotion.StackingPolicy.STACKABLE, "PERCENT", 2, BigDecimal.ONE, PromotionStatus.ACTIVE);

        EffectivePricePolicy.Result result = new EffectivePricePolicy().calculate(new BigDecimal("100"), "PEN", BigDecimal.ONE,
                List.of(exclusive, fixed, percentage), NOW);

        assertThat(result.effectivePrice()).isEqualByComparingTo("65");
        assertThat(result.discountAmount()).isEqualByComparingTo("35");
        assertThat(result.appliedPromotions()).extracting(EffectivePricePolicy.AppliedPromotion::id)
                .containsExactly(percentage.id(), fixed.id());
    }

    @Test
    void resolvesExclusiveTiesByPriorityThenStartThenStableCode() {
        PromotionCandidate earlier = candidate("44", Promotion.DiscountType.PERCENTAGE, "20", null,
                Promotion.StackingPolicy.EXCLUSIVE, "Z-CODE", 10, BigDecimal.ONE, PromotionStatus.ACTIVE);
        PromotionCandidate higherPriority = candidate("45", Promotion.DiscountType.PERCENTAGE, "20", null,
                Promotion.StackingPolicy.EXCLUSIVE, "A-CODE", 20, BigDecimal.ONE, PromotionStatus.ACTIVE);

        EffectivePricePolicy.Result result = new EffectivePricePolicy().calculate(new BigDecimal("100"), "PEN", BigDecimal.ONE,
                List.of(earlier, higherPriority), NOW);

        assertThat(result.appliedPromotions()).extracting(EffectivePricePolicy.AppliedPromotion::id)
                .containsExactly(higherPriority.id());
    }

    @Test
    void supportsStartedScheduledPromotionsAndFloorsFixedDiscountAtZero() {
        PromotionCandidate scheduled = candidate("46", Promotion.DiscountType.PERCENTAGE, "10", null,
                Promotion.StackingPolicy.EXCLUSIVE, "SCHEDULED", 0, BigDecimal.ONE, PromotionStatus.SCHEDULED);
        PromotionCandidate excessive = candidate("47", Promotion.DiscountType.FIXED_AMOUNT, "120", "PEN",
                Promotion.StackingPolicy.EXCLUSIVE, "EXCESSIVE", 0, BigDecimal.ONE, PromotionStatus.ACTIVE);

        EffectivePricePolicy.Result result = new EffectivePricePolicy().calculate(new BigDecimal("100"), "PEN", BigDecimal.ONE,
                List.of(scheduled, excessive), NOW);

        assertThat(result.effectivePrice()).isEqualByComparingTo("0");
        assertThat(result.discountAmount()).isEqualByComparingTo("100");
        assertThat(result.appliedPromotions()).extracting(EffectivePricePolicy.AppliedPromotion::id)
                .containsExactly(excessive.id());
    }

    private static PromotionCandidate candidate(String id, Promotion.DiscountType type, String value, String currency,
            Promotion.StackingPolicy stacking, String ignored) {
        return candidate(id, type, value, currency, stacking, ignored, 0, BigDecimal.ONE, PromotionStatus.ACTIVE);
    }

    private static PromotionCandidate candidate(String id, Promotion.DiscountType type, String value, String currency,
            Promotion.StackingPolicy stacking, String stableCode, int priority, BigDecimal minimumQuantity, PromotionStatus status) {
        return new PromotionCandidate(UUID.fromString("00000000-0000-0000-0000-0000000000" + id), "Promotion " + id,
                stableCode, type, new BigDecimal(value), currency, NOW.minusSeconds(60), NOW.plusSeconds(60), minimumQuantity,
                stacking, status, priority, List.of(), List.of());
    }
}
