package com.nexa.api.catalogmanagement.domain.model.pricing;

import com.nexa.api.catalogmanagement.domain.model.promotion.PromotionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/** Central, deterministic eligibility policy for server-side effective pricing. */
public final class PromotionEligibilityPolicy {
	public boolean isEligible(PromotionCandidate promotion, BigDecimal quantity, String currency, Instant asOf) {
		return isEligible(promotion, new EligibilityContext(quantity, currency,
				quantity == null ? null : quantity.multiply(java.math.BigDecimal.ONE), null, null), asOf);
	}

	public boolean isEligible(PromotionCandidate promotion, EligibilityContext context, Instant asOf) {
		if (promotion == null || context == null || promotion.status() != PromotionStatus.ACTIVE || asOf == null) return false;
		if (promotion.startsAt() != null && asOf.isBefore(promotion.startsAt())) return false;
		if (promotion.endsAt() != null && !asOf.isBefore(promotion.endsAt())) return false;
		if (context.quantity() == null || context.quantity().compareTo(promotion.minimumQuantity()) < 0) return false;
		if (promotion.discountType() == com.nexa.api.catalogmanagement.domain.model.promotion.Promotion.DiscountType.FIXED_AMOUNT
				&& (promotion.currency() == null || !promotion.currency().equals(context.currency()))) return false;
		for (PromotionCandidate.PromotionRule rule : promotion.rules()) {
			switch (rule.type()) {
				case "MIN_ORDER_AMOUNT" -> {
					try {
						if (context.orderAmount() == null || context.orderAmount().compareTo(new BigDecimal(rule.value())) < 0) return false;
					} catch (NumberFormatException ignored) { return false; }
				}
				case "CURRENCY" -> { if (!rule.value().equalsIgnoreCase(context.currency())) return false; }
				case "CLIENT_SEGMENT" -> { if (context.clientSegment() == null || !rule.value().equalsIgnoreCase(context.clientSegment())) return false; }
				case "BUYER_TIER" -> { if (context.buyerTier() == null || !rule.value().equalsIgnoreCase(context.buyerTier())) return false; }
				default -> { return false; }
			}
		}
		return true;
	}

	public record EligibilityContext(BigDecimal quantity, String currency, BigDecimal orderAmount,
			String clientSegment, String buyerTier) {
		public EligibilityContext {
			currency = currency == null ? null : currency.strip().toUpperCase(Locale.ROOT);
			clientSegment = clientSegment == null ? null : clientSegment.strip();
			buyerTier = buyerTier == null ? null : buyerTier.strip();
		}
	}
}
