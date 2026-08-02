package com.nexa.api.catalogmanagement.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
		basePrice = Objects.requireNonNullElse(basePrice, BigDecimal.ZERO);
		effectivePrice = Objects.requireNonNullElse(effectivePrice, basePrice);
		discountAmount = Objects.requireNonNullElse(discountAmount, BigDecimal.ZERO);
		currency = Objects.requireNonNullElse(currency, "PEN");
		appliedPromotions = List.copyOf(Objects.requireNonNullElse(appliedPromotions, List.of()));
		pricingAsOf = Objects.requireNonNull(pricingAsOf, "Pricing instant is required");
	}

	public record AppliedPromotion(String id, String name, String discountType, BigDecimal discountAmount) { }

	public static CatalogPricingView base(BigDecimal amount, String currency, Instant asOf) {
		return new CatalogPricingView(amount, amount, BigDecimal.ZERO, currency, List.of(), asOf);
	}
}
