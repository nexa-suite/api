package com.nexa.api.catalogcommercialpolicy.domain.model.pricing;

import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.Promotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Applies deterministic promotion precedence while preserving non-negative money. */
public final class EffectivePricePolicy {
	private static final Comparator<PromotionCandidate> STACK_ORDER = Comparator
			.comparingInt((PromotionCandidate candidate) -> candidate.discountType() == Promotion.DiscountType.PERCENTAGE ? 0 : 1)
			.thenComparing(Comparator.comparingInt(PromotionCandidate::priority).reversed())
			.thenComparing(EffectivePricePolicy::startTime)
			.thenComparing(EffectivePricePolicy::stableCode)
			.thenComparing(candidate -> candidate.id().toString());

	private final PromotionEligibilityPolicy eligibility = new PromotionEligibilityPolicy();

	public Result calculate(BigDecimal basePrice, String currency, BigDecimal quantity,
			List<PromotionCandidate> candidates, Instant asOf) {
		return calculate(basePrice, currency, quantity, null, null, null, candidates, asOf);
	}

	public Result calculate(BigDecimal basePrice, String currency, BigDecimal quantity, String clientSegment,
			String buyerTier, List<PromotionCandidate> candidates, Instant asOf) {
		return calculate(basePrice, currency, quantity, null, clientSegment, buyerTier, candidates, asOf);
	}

	public Result calculate(BigDecimal basePrice, String currency, BigDecimal quantity, UUID clientAccountId,
			String clientSegment, String buyerTier, List<PromotionCandidate> candidates, Instant asOf) {
		BigDecimal base = nonNegative(basePrice);
		String normalizedCurrency = currency == null ? null : currency.strip().toUpperCase(java.util.Locale.ROOT);
		BigDecimal orderAmount = quantity == null ? null : base.multiply(quantity);
		PromotionEligibilityPolicy.EligibilityContext context = new PromotionEligibilityPolicy.EligibilityContext(
				quantity, normalizedCurrency, orderAmount, clientSegment, buyerTier, clientAccountId);
		List<PromotionCandidate> eligible = (candidates == null ? List.<PromotionCandidate>of() : candidates).stream()
				.filter(candidate -> eligibility.isEligible(candidate, context, asOf))
				.sorted(Comparator.comparing(EffectivePricePolicy::stableCode)
						.thenComparing(candidate -> candidate.id().toString()))
				.toList();

		Selection selection = select(eligible, base, normalizedCurrency);
		AppliedResult applied = apply(base, normalizedCurrency, selection.promotions());
		return new Result(base, applied.effectivePrice(), applied.discountAmount(), applied.appliedPromotions());
	}

	private static Selection select(List<PromotionCandidate> eligible, BigDecimal base, String currency) {
		if (eligible.isEmpty()) return emptySelection(base);
		Selection bestExclusive = eligible.stream()
				.filter(candidate -> candidate.stackingPolicy() == Promotion.StackingPolicy.EXCLUSIVE)
				.map(candidate -> selection(base, currency, List.of(candidate)))
				.min(EffectivePricePolicy::compareSelections)
				.orElse(null);
		List<PromotionCandidate> stackable = eligible.stream()
				.filter(candidate -> candidate.stackingPolicy() == Promotion.StackingPolicy.STACKABLE)
				.sorted(STACK_ORDER)
				.toList();
		Selection bestStackable = stackable.isEmpty() ? null : selection(base, currency, stackable);
		if (bestExclusive == null) return bestStackable == null ? emptySelection(base) : bestStackable;
		if (bestStackable == null) return bestExclusive;
		return compareSelections(bestExclusive, bestStackable) <= 0 ? bestExclusive : bestStackable;
	}

	private static Selection selection(BigDecimal base, String currency, List<PromotionCandidate> promotions) {
		AppliedResult applied = apply(base, currency, promotions);
		int priority = promotions.stream().mapToInt(PromotionCandidate::priority).max().orElse(0);
		String stableCode = promotions.stream().map(EffectivePricePolicy::stableCode).reduce((left, right) -> left + "|" + right).orElse("");
		return new Selection(promotions, applied.effectivePrice(), applied.discountAmount(), priority, startTime(promotions.getFirst()), stableCode,
				promotions.getFirst().id().toString());
	}

	private static Selection emptySelection(BigDecimal base) {
		return new Selection(List.of(), base, BigDecimal.ZERO, 0, Instant.MAX, "", "");
	}

	private static int compareSelections(Selection left, Selection right) {
		int byDiscount = right.discountAmount().compareTo(left.discountAmount());
		if (byDiscount != 0) return byDiscount;
		int byPriority = Integer.compare(right.priority(), left.priority());
		if (byPriority != 0) return byPriority;
		int byStart = left.startTime().compareTo(right.startTime());
		if (byStart != 0) return byStart;
		int byCode = left.stableCode().compareTo(right.stableCode());
		return byCode != 0 ? byCode : left.technicalId().compareTo(right.technicalId());
	}

	private static AppliedResult apply(BigDecimal base, String currency, List<PromotionCandidate> promotions) {
		BigDecimal current = base;
		List<AppliedPromotion> applied = new ArrayList<>();
		for (PromotionCandidate promotion : promotions) {
			BigDecimal discount = discount(current, promotion, currency);
			if (discount.signum() <= 0) continue;
			current = nonNegative(current.subtract(discount));
			applied.add(new AppliedPromotion(promotion.id(), promotion.name(), promotion.discountType().name(), discount));
		}
		BigDecimal effective = nonNegative(current);
		return new AppliedResult(effective, nonNegative(base.subtract(effective)), applied);
	}

	private static BigDecimal discount(BigDecimal amount, PromotionCandidate promotion, String currency) {
		if (promotion.discountType() == Promotion.DiscountType.PERCENTAGE) {
			return nonNegative(amount.multiply(promotion.discountValue())
					.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).min(amount));
		}
		if (promotion.currency() == null || currency == null || !promotion.currency().equals(currency)) return BigDecimal.ZERO;
		return nonNegative(promotion.discountValue().min(amount));
	}

	private static String stableCode(PromotionCandidate candidate) {
		String stableCode = candidate.stableCode();
		if (stableCode != null && !stableCode.isBlank()) return stableCode.strip().toUpperCase(java.util.Locale.ROOT);
		if (candidate.name() != null && !candidate.name().isBlank()) return candidate.name().strip().toUpperCase(java.util.Locale.ROOT);
		return candidate.id().toString();
	}

	private static BigDecimal nonNegative(BigDecimal value) {
		if (value == null) return BigDecimal.ZERO;
		return value.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
	}

	private record Selection(List<PromotionCandidate> promotions, BigDecimal effectivePrice, BigDecimal discountAmount,
			int priority, Instant startTime, String stableCode, String technicalId) {
		private Selection {
			promotions = List.copyOf(promotions);
			startTime = startTime == null ? Instant.MAX : startTime;
		}
	}

	private record AppliedResult(BigDecimal effectivePrice, BigDecimal discountAmount, List<AppliedPromotion> appliedPromotions) { }

	private static Instant startTime(PromotionCandidate candidate) {
		return candidate.startsAt() == null ? Instant.MAX : candidate.startsAt();
	}

	public record Result(BigDecimal basePrice, BigDecimal effectivePrice, BigDecimal discountAmount,
			List<AppliedPromotion> appliedPromotions) {
		public Result {
			basePrice = nonNegative(basePrice);
			effectivePrice = nonNegative(effectivePrice);
			discountAmount = nonNegative(discountAmount);
			if (effectivePrice.compareTo(basePrice) > 0) throw new IllegalArgumentException("Effective price cannot exceed base price");
			if (discountAmount.compareTo(basePrice) > 0) throw new IllegalArgumentException("Discount cannot exceed base price");
			appliedPromotions = List.copyOf(appliedPromotions == null ? List.of() : appliedPromotions);
		}

		public BigDecimal totalEffectivePrice(BigDecimal quantity) {
			return multiply(effectivePrice, quantity);
		}

		public BigDecimal totalDiscountAmount(BigDecimal quantity) {
			return multiply(discountAmount, quantity);
		}

		private static BigDecimal multiply(BigDecimal amount, BigDecimal quantity) {
			if (quantity == null || quantity.signum() < 0) throw new IllegalArgumentException("Pricing quantity is invalid");
			return amount.multiply(quantity).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
		}
	}

	public record AppliedPromotion(UUID id, String name, String discountType, BigDecimal discountAmount) {
		public AppliedPromotion {
			if (id == null) throw new IllegalArgumentException("Applied promotion id is required");
			name = name == null ? "" : name.strip();
			discountType = discountType == null ? "" : discountType.strip();
			discountAmount = nonNegative(discountAmount);
		}
	}
}
