package com.nexa.api.catalogcommercialpolicy.domain.model.pricing;

import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.Promotion;
import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.PromotionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativePriceResolverTests {
    private static final Instant AS_OF = Instant.parse("2026-09-25T12:00:00Z");

    @Test
    void appliesOnlyTheCustomerPermittedPriceListBeforeOnePromotion() {
        UUID priceListId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        PromotionCandidate promotion = new PromotionCandidate(
                UUID.fromString("20000000-0000-0000-0000-000000000001"), "Ten percent", "TEN-PERCENT",
                Promotion.DiscountType.PERCENTAGE, new BigDecimal("10"), null,
                AS_OF.minusSeconds(60), AS_OF.plusSeconds(60), BigDecimal.ONE,
                Promotion.StackingPolicy.STACKABLE, PromotionStatus.ACTIVE, 1, List.of(), List.of());

        AuthoritativePriceResolver.ResolvedOffer result = new AuthoritativePriceResolver().resolve(
                new BigDecimal("100"), "PEN",
                new AuthoritativePriceResolver.PriceListPrice(priceListId, new BigDecimal("200"), "PEN"),
                new AuthoritativePriceResolver.CustomerTerms(priceListId, "PEN"),
                UUID.fromString("30000000-0000-0000-0000-000000000001"), "HOSPITAL", null,
                new BigDecimal("3"), List.of(promotion), AS_OF);

        assertThat(result.basePrice()).isEqualByComparingTo("200");
        assertThat(result.effectivePrice()).isEqualByComparingTo("180");
        assertThat(result.discountAmount()).isEqualByComparingTo("20");
        assertThat(result.appliedPromotions()).hasSize(1);
        assertThat(result.effectiveAt()).isEqualTo(AS_OF);
    }

    @Test
    void ignoresPriceListThatCustomerTermsDoNotPermit() {
        AuthoritativePriceResolver.ResolvedOffer result = new AuthoritativePriceResolver().resolve(
                new BigDecimal("100"), "PEN",
                new AuthoritativePriceResolver.PriceListPrice(
                        UUID.fromString("10000000-0000-0000-0000-000000000001"), new BigDecimal("200"), "PEN"),
                new AuthoritativePriceResolver.CustomerTerms(
                        UUID.fromString("10000000-0000-0000-0000-000000000002"), "PEN"),
                UUID.fromString("30000000-0000-0000-0000-000000000001"), null, null,
                BigDecimal.ONE, List.of(), AS_OF);

        assertThat(result.basePrice()).isEqualByComparingTo("100");
        assertThat(result.effectivePrice()).isEqualByComparingTo("100");
    }

    @Test
    void rejectsCustomerTermsCurrencyMismatch() {
        assertThatThrownBy(() -> new AuthoritativePriceResolver().resolve(
                new BigDecimal("100"), "PEN", null,
                new AuthoritativePriceResolver.CustomerTerms(null, "USD"),
                UUID.fromString("30000000-0000-0000-0000-000000000001"), null, null,
                BigDecimal.ONE, List.of(), AS_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currency");
    }
}
