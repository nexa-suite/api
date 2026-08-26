package com.nexa.api.catalogcommercialpolicy.domain.model.pricing;

import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.Promotion;
import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.PromotionStatus;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

/** Immutable promotion data rehydrated at the Catalog application boundary. */
public record PromotionCandidate(UUID id, String name, Promotion.DiscountType discountType,
		String stableCode, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
		BigDecimal minimumQuantity, Promotion.StackingPolicy stackingPolicy, PromotionStatus status, int priority,
		List<UUID> clientAccountIds, List<PromotionRule> rules) {
	public PromotionCandidate(UUID id, String name, Promotion.DiscountType discountType,
			BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, Promotion.StackingPolicy stackingPolicy, PromotionStatus status) {
		this(id, name, discountType, name, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, status,
				0, List.of(), List.of());
	}

	public PromotionCandidate(UUID id, String name, Promotion.DiscountType discountType,
			BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, Promotion.StackingPolicy stackingPolicy, PromotionStatus status,
			List<PromotionRule> rules) {
		this(id, name, discountType, name, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, status,
				0, List.of(), rules);
	}

	public PromotionCandidate(UUID id, String name, String stableCode, Promotion.DiscountType discountType,
			BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, Promotion.StackingPolicy stackingPolicy, PromotionStatus status, int priority,
			List<UUID> clientAccountIds, List<PromotionRule> rules) {
		this(id, name, discountType, stableCode, discountValue, currency, startsAt, endsAt, minimumQuantity,
				stackingPolicy, status, priority, clientAccountIds, rules);
	}

	public PromotionCandidate {
		id = Objects.requireNonNull(id, "Promotion id is required");
		name = Objects.requireNonNullElse(name, "").strip();
		stableCode = Objects.requireNonNullElse(stableCode, "").strip();
		discountType = Objects.requireNonNull(discountType, "Promotion discount type is required");
		discountValue = normalizeDiscount(Objects.requireNonNull(discountValue, "Promotion discount value is required"));
		if (discountValue.signum() < 0
				|| discountType == Promotion.DiscountType.PERCENTAGE
				&& discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
			throw new IllegalArgumentException("Promotion discount is invalid");
		}
		currency = normalizeCurrency(discountType, currency);
		if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
			throw new IllegalArgumentException("Promotion period is invalid");
		}
		minimumQuantity = minimumQuantity == null ? BigDecimal.ONE : minimumQuantity.stripTrailingZeros();
		if (minimumQuantity.signum() <= 0) throw new IllegalArgumentException("Promotion quantity is invalid");
		stackingPolicy = Objects.requireNonNull(stackingPolicy, "Promotion stacking policy is required");
		status = Objects.requireNonNull(status, "Promotion status is required");
		if (priority < Promotion.MIN_PRIORITY || priority > Promotion.MAX_PRIORITY) {
			throw new IllegalArgumentException("Promotion priority is invalid");
		}
		clientAccountIds = distinctIds(clientAccountIds);
		rules = rules == null ? List.of() : List.copyOf(rules);
	}

	public record PromotionRule(String type, String value) {
		public PromotionRule {
			type = Objects.requireNonNullElse(type, "").strip().toUpperCase(Locale.ROOT);
			value = Objects.requireNonNullElse(value, "").strip();
		}
	}

	private static String normalizeCurrency(Promotion.DiscountType type, String value) {
		if (type == Promotion.DiscountType.PERCENTAGE) {
			if (value != null && !value.isBlank()) throw new IllegalArgumentException("Percentage promotion cannot define currency");
			return null;
		}
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Fixed promotion currency is required");
		String normalized = value.strip().toUpperCase(Locale.ROOT);
		Money.from(BigDecimal.ZERO, normalized);
		return normalized;
	}

	private static List<UUID> distinctIds(List<UUID> ids) {
		if (ids == null || ids.isEmpty()) return List.of();
		return List.copyOf(new LinkedHashSet<>(new ArrayList<>(ids.stream()
				.map(value -> Objects.requireNonNull(value, "Promotion client account id is required")).toList())));
	}

	private static BigDecimal normalizeDiscount(BigDecimal value) {
		try { return value.setScale(2, RoundingMode.UNNECESSARY).stripTrailingZeros(); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException("Promotion discount cannot have more than two decimals", exception); }
	}
}
