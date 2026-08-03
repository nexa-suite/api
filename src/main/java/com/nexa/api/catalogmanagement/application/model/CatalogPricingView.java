package com.nexa.api.catalogmanagement.application.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Server-calculated commercial price; clients must not reconstruct it. */
public record CatalogPricingView(
		BigDecimal basePrice,
		BigDecimal effectivePrice,
		BigDecimal discountAmount,
		String currency,
		List<AppliedPromotion> appliedPromotions,
		Instant pricingAsOf) {
	public CatalogPricingView {
		basePrice = nonNegative(Objects.requireNonNullElse(basePrice, BigDecimal.ZERO), "Base price");
		effectivePrice = nonNegative(Objects.requireNonNullElse(effectivePrice, basePrice), "Effective price");
		discountAmount = nonNegative(Objects.requireNonNullElse(discountAmount, BigDecimal.ZERO), "Discount amount");
		if (effectivePrice.compareTo(basePrice) > 0) throw new IllegalArgumentException("Effective price cannot exceed base price");
		if (discountAmount.compareTo(basePrice) > 0) throw new IllegalArgumentException("Discount amount cannot exceed base price");
		if (basePrice.subtract(effectivePrice).compareTo(discountAmount) != 0) {
			throw new IllegalArgumentException("Pricing amounts are inconsistent");
		}
		currency = Objects.requireNonNullElse(currency, "PEN").strip().toUpperCase(Locale.ROOT);
		if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Pricing currency is invalid");
		appliedPromotions = List.copyOf(Objects.requireNonNullElse(appliedPromotions, List.of()));
		pricingAsOf = Objects.requireNonNull(pricingAsOf, "Pricing instant is required");
	}

	public record AppliedPromotion(String id, String name, String discountType, BigDecimal discountAmount) {
		public AppliedPromotion {
			id = Objects.requireNonNull(id, "Applied promotion id is required");
			discountAmount = nonNegative(discountAmount, "Applied promotion discount amount");
		}
	}

	public static CatalogPricingView base(BigDecimal amount, String currency, Instant asOf) {
		return new CatalogPricingView(amount, amount, BigDecimal.ZERO, currency, List.of(), asOf);
	}

	public BigDecimal baseTotal(BigDecimal quantity) {
		return total(basePrice, quantity);
	}

	public BigDecimal effectiveTotal(BigDecimal quantity) {
		return total(effectivePrice, quantity);
	}

	public BigDecimal discountTotal(BigDecimal quantity) {
		return total(discountAmount, quantity);
	}

	private static BigDecimal total(BigDecimal amount, BigDecimal quantity) {
		if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("Pricing quantity is invalid");
		return amount.multiply(quantity).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
	}

	private static BigDecimal nonNegative(BigDecimal value, String label) {
		if (value == null) throw new IllegalArgumentException(label + " is required");
		if (value.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative");
		try { return value.setScale(2, RoundingMode.UNNECESSARY).stripTrailingZeros(); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException(label + " cannot have more than two decimals", exception); }
	}
}
