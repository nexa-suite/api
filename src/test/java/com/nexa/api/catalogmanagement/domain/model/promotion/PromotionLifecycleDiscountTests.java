package com.nexa.api.catalogmanagement.domain.model.promotion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionLifecycleDiscountTests {
	private static final Instant START = Instant.parse("2026-03-01T00:00:00Z");
	private static final Instant END = Instant.parse("2026-04-01T00:00:00Z");

	@Test
	void startsAsDraftAndExposesDiscountDetails() {
		Promotion promotion = promotion(Promotion.DiscountType.PERCENTAGE, new BigDecimal("15.50"));

		assertThat(promotion.status()).isEqualTo(PromotionStatus.DRAFT);
		assertThat(promotion.discountType()).isEqualTo(Promotion.DiscountType.PERCENTAGE);
		assertThat(promotion.discountValue()).isEqualByComparingTo("15.50");
		assertThat(promotion.startsAt()).isEqualTo(START);
		assertThat(promotion.endsAt()).isEqualTo(END);
	}

	@Test
	void supportsScheduleActivatePauseResumeAndCancelLifecycle() {
		Promotion promotion = promotion(Promotion.DiscountType.PERCENTAGE, new BigDecimal("10"));

		promotion.schedule();
		assertThat(promotion.status()).isEqualTo(PromotionStatus.SCHEDULED);
		promotion.activate();
		assertThat(promotion.status()).isEqualTo(PromotionStatus.ACTIVE);
		promotion.pause();
		assertThat(promotion.status()).isEqualTo(PromotionStatus.PAUSED);
		promotion.activate();
		assertThat(promotion.status()).isEqualTo(PromotionStatus.ACTIVE);
		promotion.cancel();
		assertThat(promotion.status()).isEqualTo(PromotionStatus.CANCELLED);

		Promotion direct = promotion(Promotion.DiscountType.FIXED_AMOUNT, new BigDecimal("5.25"));
		direct.activate();
		assertThat(direct.status()).isEqualTo(PromotionStatus.ACTIVE);
	}

	@Test
	void rejectsInvalidLifecycleTransitions() {
		Promotion draft = promotion(Promotion.DiscountType.PERCENTAGE, BigDecimal.TEN);
		assertThatThrownBy(draft::pause).isInstanceOf(IllegalStateException.class);
		draft.schedule();
		assertThatThrownBy(draft::schedule).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(draft::pause).isInstanceOf(IllegalStateException.class);
		draft.activate();
		assertThatThrownBy(draft::schedule).isInstanceOf(IllegalStateException.class);
		draft.pause();
		assertThatThrownBy(draft::pause).isInstanceOf(IllegalStateException.class);
		draft.cancel();
		assertThatThrownBy(draft::activate).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(draft::cancel).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void acceptsBoundaryPercentageFixedAmountAndOpenPeriodDiscounts() {
		Promotion zeroPercentage = promotion(Promotion.DiscountType.PERCENTAGE, BigDecimal.ZERO);
		Promotion fullPercentage = promotion(Promotion.DiscountType.PERCENTAGE, new BigDecimal("100"));
		Promotion fixedAmount = promotion(Promotion.DiscountType.FIXED_AMOUNT, new BigDecimal("25.40"));
		Promotion openStart = Promotion.create(UUID.randomUUID(), Promotion.DiscountType.PERCENTAGE, BigDecimal.ONE, null, END);
		Promotion openEnd = Promotion.create(UUID.randomUUID(), Promotion.DiscountType.PERCENTAGE, BigDecimal.ONE, START, null);

		assertThat(zeroPercentage.discountValue()).isEqualByComparingTo("0");
		assertThat(fullPercentage.discountValue()).isEqualByComparingTo("100");
		assertThat(fixedAmount.discountValue()).isEqualByComparingTo("25.40");
		assertThat(openStart.startsAt()).isNull();
		assertThat(openEnd.endsAt()).isNull();
	}

	@Test
	void validatesDiscountIdentityValueAndPeriodInvariants() {
		assertThatThrownBy(() -> Promotion.create(null, Promotion.DiscountType.PERCENTAGE, BigDecimal.ONE, START, END))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Promotion.create(UUID.randomUUID(), null, BigDecimal.ONE, START, END))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Promotion.create(UUID.randomUUID(), Promotion.DiscountType.PERCENTAGE, null, START, END))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> promotion(Promotion.DiscountType.PERCENTAGE, new BigDecimal("-0.01")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> promotion(Promotion.DiscountType.FIXED_AMOUNT, new BigDecimal("-0.01")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> promotion(Promotion.DiscountType.PERCENTAGE, new BigDecimal("100.01")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Promotion.create(UUID.randomUUID(), Promotion.DiscountType.PERCENTAGE, BigDecimal.ONE, START, START))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Promotion.create(UUID.randomUUID(), Promotion.DiscountType.PERCENTAGE, BigDecimal.ONE, END, START))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static Promotion promotion(Promotion.DiscountType type, BigDecimal value) {
		return Promotion.create(UUID.fromString("77777777-7777-7777-7777-777777777777"), type, value, START, END);
	}
}
