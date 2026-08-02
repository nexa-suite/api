package com.nexa.api.catalogmanagement.domain.model.pricing;

import com.nexa.api.catalogmanagement.domain.model.promotion.Promotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Applies promotion rules once, preserving a non-negative commercial price. */
public final class EffectivePricePolicy {
	private final PromotionEligibilityPolicy eligibility = new PromotionEligibilityPolicy();

	public Result calculate(BigDecimal basePrice, String currency, BigDecimal quantity,
			List<PromotionCandidate> candidates, Instant asOf) {
		return calculate(basePrice, currency, quantity, null, null, candidates, asOf);
	}

	public Result calculate(BigDecimal basePrice, String currency, BigDecimal quantity, String clientSegment,
			String buyerTier, List<PromotionCandidate> candidates, Instant asOf) {
		BigDecimal base = nonNegative(basePrice);
		PromotionEligibilityPolicy.EligibilityContext context = new PromotionEligibilityPolicy.EligibilityContext(
				quantity, currency, base.multiply(quantity == null ? BigDecimal.ZERO : quantity), clientSegment, buyerTier);
		List<PromotionCandidate> eligible = (candidates == null ? List.<PromotionCandidate>of() : candidates).stream()
				.filter(candidate -> eligibility.isEligible(candidate, context, asOf))
				.sorted(Comparator.comparing(candidate -> candidate.id().toString()))
				.toList();
		List<PromotionCandidate> selected = selectStacking(eligible, base, currency);
		BigDecimal current = base;
		List<AppliedPromotion> applied = new ArrayList<>();
		for (PromotionCandidate promotion : selected) {
			BigDecimal discount = discount(current, promotion, currency);
			if (discount.signum() <= 0) continue;
			current = current.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
			applied.add(new AppliedPromotion(promotion.id(), promotion.name(), promotion.discountType().name(), discount));
		}
		BigDecimal effective = current.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
		return new Result(base, effective, base.subtract(effective).max(BigDecimal.ZERO), applied);
	}

	private static List<PromotionCandidate> selectStacking(List<PromotionCandidate> eligible, BigDecimal base, String currency) {
		List<PromotionCandidate> stackable = eligible.stream().filter(candidate -> candidate.stackingPolicy() == Promotion.StackingPolicy.STACKABLE).toList();
		List<PromotionCandidate> exclusive = eligible.stream().filter(candidate -> candidate.stackingPolicy() == Promotion.StackingPolicy.EXCLUSIVE).toList();
		PromotionCandidate bestExclusive = exclusive.stream().max(Comparator.comparing(candidate -> discount(base, candidate, currency))).orElse(null);
		if (bestExclusive == null) return stackable;
		if (stackable.isEmpty()) return List.of(bestExclusive);
		return List.of(bestExclusive);
	}

	private static BigDecimal discount(BigDecimal amount, PromotionCandidate promotion, String currency) {
		if (promotion.discountType() == Promotion.DiscountType.PERCENTAGE) {
			return amount.multiply(promotion.discountValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).min(amount);
		}
		if (!promotion.currency().equals(currency)) return BigDecimal.ZERO;
		return promotion.discountValue().min(amount);
	}

	private static BigDecimal nonNegative(BigDecimal value) { return (value == null ? BigDecimal.ZERO : value).max(BigDecimal.ZERO); }

	public record Result(BigDecimal basePrice, BigDecimal effectivePrice, BigDecimal discountAmount,
			List<AppliedPromotion> appliedPromotions) {
		public Result { appliedPromotions = List.copyOf(appliedPromotions); }
	}
	public record AppliedPromotion(java.util.UUID id, String name, String discountType, BigDecimal discountAmount) { }
}
