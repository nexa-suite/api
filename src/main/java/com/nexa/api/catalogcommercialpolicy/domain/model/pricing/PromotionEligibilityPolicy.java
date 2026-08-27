package com.nexa.api.catalogcommercialpolicy.domain.model.pricing;

import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.PromotionStatus;
import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.Promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Central, deterministic eligibility policy for server-side effective pricing. */
public final class PromotionEligibilityPolicy {
	private final ClientAccountEligibilityPolicy clientAccounts = new ClientAccountEligibilityPolicy();

	public boolean isEligible(PromotionCandidate promotion, BigDecimal quantity, String currency, Instant asOf) {
		return isEligible(promotion, new EligibilityContext(quantity, currency,
				quantity == null ? null : quantity.multiply(java.math.BigDecimal.ONE), null, null), asOf);
	}

	public boolean isEligible(PromotionCandidate promotion, EligibilityContext context, Instant asOf) {
		if (promotion == null || context == null || asOf == null) return false;
		boolean active = promotion.status() == PromotionStatus.ACTIVE;
		boolean started = promotion.status() == PromotionStatus.SCHEDULED
				&& promotion.startsAt() != null && !asOf.isBefore(promotion.startsAt());
		if (!active && !started) return false;
		if (promotion.startsAt() != null && asOf.isBefore(promotion.startsAt())) return false;
		if (promotion.endsAt() != null && !asOf.isBefore(promotion.endsAt())) return false;
		if (context.quantity() == null || context.quantity().signum() <= 0
				|| context.quantity().compareTo(promotion.minimumQuantity()) < 0) return false;
		if (context.orderAmount() != null && context.orderAmount().signum() < 0) return false;
		if (promotion.discountType() == Promotion.DiscountType.FIXED_AMOUNT
				&& (promotion.currency() == null || !promotion.currency().equals(context.currency()))) return false;
		if (!clientAccounts.isEligible(promotion, context.clientAccountId(), context.clientSegment(), context.buyerTier())) return false;
		for (PromotionCandidate.PromotionRule rule : promotion.rules()) {
			switch (rule.type()) {
				case "MIN_ORDER_AMOUNT" -> {
					try {
						BigDecimal minimum = new BigDecimal(rule.value());
						if (minimum.signum() < 0 || context.orderAmount() == null || context.orderAmount().compareTo(minimum) < 0) return false;
					} catch (NumberFormatException ignored) { return false; }
				}
				case "CURRENCY" -> { if (context.currency() == null || !rule.value().equalsIgnoreCase(context.currency())) return false; }
				case "CLIENT_ACCOUNT", "CLIENT_ACCOUNT_ID", "CLIENT_SEGMENT", "BUYER_TIER" -> { }
				default -> { return false; }
			}
		}
		return true;
	}

	public record EligibilityContext(BigDecimal quantity, String currency, BigDecimal orderAmount,
			String clientSegment, String buyerTier, UUID clientAccountId) {
		public EligibilityContext(BigDecimal quantity, String currency, BigDecimal orderAmount,
				String clientSegment, String buyerTier) {
			this(quantity, currency, orderAmount, clientSegment, buyerTier, null);
		}

		public EligibilityContext {
			quantity = quantity == null ? null : quantity.stripTrailingZeros();
			currency = currency == null ? null : currency.strip().toUpperCase(Locale.ROOT);
			clientSegment = normalizeToken(clientSegment);
			buyerTier = normalizeToken(buyerTier);
		}

		private static String normalizeToken(String value) {
			if (value == null || value.isBlank()) return null;
			return value.strip().toUpperCase(Locale.ROOT);
		}
	}
}
