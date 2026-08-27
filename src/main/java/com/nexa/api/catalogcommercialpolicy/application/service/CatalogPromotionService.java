package com.nexa.api.catalogcommercialpolicy.application.service;

import com.nexa.api.catalogcommercialpolicy.application.CatalogPermissions;
import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPromotionUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogPromotionPort;
import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.Promotion;
import com.nexa.api.catalogcommercialpolicy.domain.model.promotion.PromotionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class CatalogPromotionService implements CatalogPromotionUseCase {
	private static final int MAX_TEXT_LENGTH = 2_000;

	private final CatalogPromotionPort port;
	private final CatalogAuthorizationPort authorization;

	public CatalogPromotionService(CatalogPromotionPort port, CatalogAuthorizationPort authorization) {
		this.port = Objects.requireNonNull(port, "Catalog promotion port is required");
		this.authorization = Objects.requireNonNull(authorization, "Catalog authorization port is required");
	}

	@Override
	public CatalogManagementModels.Page<CatalogManagementModels.PromotionView> promotions(CatalogScope scope, int page, int size, String status) {
		authorization.require(CatalogPermissions.PROMOTION_READ);
		return port.promotions(scope, page, size, status);
	}

	@Override
	public CatalogManagementModels.PromotionView promotion(CatalogScope scope, UUID id) {
		authorization.require(CatalogPermissions.PROMOTION_READ);
		return port.promotion(scope, id);
	}

	@Override
	public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds) {
		return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, List.of(), List.of(), null, 0);
	}

	@Override
	public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			String idempotencyKey) {
		return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, List.of(), List.of(), idempotencyKey, 0);
	}

	@Override
	public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			String idempotencyKey, int priority) {
		return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, List.of(), List.of(), idempotencyKey, priority);
	}

	@Override
	public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, String idempotencyKey) {
		return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, idempotencyKey, 0);
	}

	@Override
	public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules,
			String idempotencyKey, int priority) {
		authorization.require(CatalogPermissions.PROMOTION_MANAGE);
		Promotion promotion = Promotion.create(UUID.randomUUID(), discountType(discountType), discountValue,
				startsAt, endsAt, currency, minimumQuantity, stacking(stackingPolicy), priority);
		return port.create(scope, required(slug, "Promotion slug", 140), required(name, "Promotion name", 200),
				optional(description, "Promotion description", MAX_TEXT_LENGTH), promotion.discountType().name(),
				promotion.discountValue(), promotion.currency(), promotion.startsAt(), promotion.endsAt(),
				promotion.minimumQuantity(), promotion.stackingPolicy().name(), distinct(productIds), distinct(categoryIds),
				distinct(clientAccountIds), normalizeRules(rules), idempotencyKey, promotion.priority());
	}

	@Override
	public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, long version) {
		return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, List.of(), List.of(), version, null);
	}

	@Override
	public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			long version, int priority) {
		return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, List.of(), List.of(), version, Integer.valueOf(priority));
	}

	@Override
	public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, long version) {
		return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, version, null);
	}

	@Override
	public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, long version, int priority) {
		return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
				minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, version, Integer.valueOf(priority));
	}

	private CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
			String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
			BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
			List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, long version,
			Integer requestedPriority) {
		authorization.require(CatalogPermissions.PROMOTION_MANAGE);
		CatalogManagementModels.PromotionView current = port.promotion(scope, id);
		if (current == null) throw new CatalogResourceNotFoundException("promotion");
		int priority = requestedPriority == null ? current.priority() : requestedPriority;
		Promotion promotion = Promotion.restore(UUID.fromString(current.id()), discountType(discountType), discountValue,
				startsAt, endsAt, currency, minimumQuantity, stacking(stackingPolicy), priority,
				PromotionStatus.valueOf(current.status()));
		return port.update(scope, id, required(slug, "Promotion slug", 140), required(name, "Promotion name", 200),
				optional(description, "Promotion description", MAX_TEXT_LENGTH), promotion.discountType().name(),
				promotion.discountValue(), promotion.currency(), promotion.startsAt(), promotion.endsAt(),
				promotion.minimumQuantity(), promotion.stackingPolicy().name(), distinct(productIds), distinct(categoryIds),
				distinct(clientAccountIds), normalizeRules(rules), version, promotion.priority());
	}

	@Override
	public CatalogManagementModels.PromotionView changeStatus(CatalogScope scope, UUID id, String status, long version) {
		authorization.require(CatalogPermissions.PROMOTION_MANAGE);
		CatalogManagementModels.PromotionView current = port.promotion(scope, id);
		if (current == null) throw new CatalogResourceNotFoundException("promotion");
		Promotion promotion = Promotion.restore(UUID.fromString(current.id()), discountType(current.discountType()), current.discountValue(),
				current.startsAt(), current.endsAt(), current.currency(), current.minimumQuantity(), stacking(current.stackingPolicy()),
				current.priority(), PromotionStatus.valueOf(current.status()));
		PromotionStatus target = status(status);
		if (target != promotion.status()) transition(promotion, target);
		return port.changeStatus(scope, id, target.name(), version);
	}

	private static void transition(Promotion promotion, PromotionStatus target) {
		switch (target) {
			case SCHEDULED -> promotion.schedule();
			case ACTIVE -> promotion.activate();
			case PAUSED -> promotion.pause();
			case EXPIRED -> promotion.expire();
			case CANCELLED -> promotion.cancel();
			case DRAFT -> throw new IllegalStateException("Promotion cannot return to DRAFT");
		}
	}

	private static Promotion.DiscountType discountType(String value) {
		if (value == null) throw new IllegalArgumentException("Promotion discount type is required");
		return Promotion.DiscountType.valueOf(value.strip().toUpperCase(Locale.ROOT));
	}

	private static Promotion.StackingPolicy stacking(String value) {
		return value == null || value.isBlank() ? Promotion.StackingPolicy.EXCLUSIVE
				: Promotion.StackingPolicy.valueOf(value.strip().toUpperCase(Locale.ROOT));
	}

	private static PromotionStatus status(String value) {
		if (value == null) throw new IllegalArgumentException("Promotion status is required");
		return PromotionStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
	}

	private static List<UUID> distinct(List<UUID> values) {
		if (values == null || values.isEmpty()) return List.of();
		return List.copyOf(new LinkedHashSet<>(values.stream().map(value -> Objects.requireNonNull(value, "Promotion target id is required")).toList()));
	}

	private static List<CatalogManagementModels.PromotionRuleView> normalizeRules(List<CatalogManagementModels.PromotionRuleView> rules) {
		if (rules == null || rules.isEmpty()) return List.of();
		return rules.stream().map(rule -> {
			if (rule == null) throw new IllegalArgumentException("Promotion rule is required");
			String type = required(rule.type(), "Promotion rule type", 40).toUpperCase(Locale.ROOT);
			if (!List.of("MIN_ORDER_AMOUNT", "CLIENT_ACCOUNT", "CLIENT_ACCOUNT_ID", "CLIENT_SEGMENT", "BUYER_TIER", "CURRENCY").contains(type)) {
				throw new IllegalArgumentException("Promotion rule type is invalid");
			}
			String value = required(rule.value(), "Promotion rule value", 255);
			if ("MIN_ORDER_AMOUNT".equals(type)) {
				BigDecimal amount = new BigDecimal(value);
				if (amount.signum() < 0) throw new IllegalArgumentException("Promotion minimum order amount is invalid");
				value = amount.stripTrailingZeros().toPlainString();
			}
			return new CatalogManagementModels.PromotionRuleView(type, value);
		}).toList();
	}

	private static String required(String value, String label, int max) {
		String normalized = value == null ? "" : value.strip();
		if (normalized.isBlank() || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
		return normalized;
	}

	private static String optional(String value, String label, int max) {
		if (value == null || value.isBlank()) return null;
		String normalized = value.strip();
		if (normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
		return normalized;
	}
}
