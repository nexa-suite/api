package com.nexa.api.catalogmanagement.domain.model.pricing;

import com.nexa.api.catalogmanagement.domain.model.promotion.Promotion;
import com.nexa.api.catalogmanagement.domain.model.promotion.PromotionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

/** Immutable promotion data rehydrated at the Catalog application boundary. */
public record PromotionCandidate(UUID id, String name, Promotion.DiscountType discountType,
		BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
		BigDecimal minimumQuantity, Promotion.StackingPolicy stackingPolicy, PromotionStatus status,
		List<PromotionRule> rules) {
	public PromotionCandidate(UUID id, String name, Promotion.DiscountType discountType,
			BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, Promotion.StackingPolicy stackingPolicy, PromotionStatus status) {
		this(id, name, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, status, List.of());
	}

	public PromotionCandidate {
		id = Objects.requireNonNull(id, "Promotion id is required");
		name = Objects.requireNonNullElse(name, "").strip();
		discountType = Objects.requireNonNull(discountType, "Promotion discount type is required");
		discountValue = Objects.requireNonNull(discountValue, "Promotion discount value is required");
		minimumQuantity = minimumQuantity == null ? BigDecimal.ONE : minimumQuantity;
		stackingPolicy = Objects.requireNonNull(stackingPolicy, "Promotion stacking policy is required");
		status = Objects.requireNonNull(status, "Promotion status is required");
		rules = rules == null ? List.of() : List.copyOf(rules);
	}

	public record PromotionRule(String type, String value) {
		public PromotionRule {
			type = Objects.requireNonNullElse(type, "").strip().toUpperCase(java.util.Locale.ROOT);
			value = Objects.requireNonNullElse(value, "").strip();
		}
	}
}
