package com.nexa.api.catalogcommercialpolicy.application.service;

import com.nexa.api.catalogcommercialpolicy.application.CatalogPermissions;
import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingPreviewModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPricingPreviewUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogPricingPreviewPort;
import com.nexa.api.catalogcommercialpolicy.domain.model.pricing.EffectivePricePolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure orchestration of quantity previews; buyer identity comes from the catalog scope. */
public final class CatalogPricingPreviewService implements CatalogPricingPreviewUseCase {
	private final CatalogPricingPreviewPort port;
	private final CatalogAuthorizationPort authorization;
	private final EffectivePricePolicy pricing;
	private final Clock clock;

	public CatalogPricingPreviewService(CatalogPricingPreviewPort port, CatalogAuthorizationPort authorization, Clock clock) {
		this(port, authorization, new EffectivePricePolicy(), clock);
	}

	public CatalogPricingPreviewService(CatalogPricingPreviewPort port, CatalogAuthorizationPort authorization,
			EffectivePricePolicy pricing, Clock clock) {
		this.port = Objects.requireNonNull(port, "Catalog pricing preview port is required");
		this.authorization = Objects.requireNonNull(authorization, "Catalog authorization port is required");
		this.pricing = Objects.requireNonNull(pricing, "Effective price policy is required");
		this.clock = Objects.requireNonNull(clock, "Pricing preview clock is required");
	}

	@Override
	public CatalogPricingPreviewModels.Result preview(CatalogScope scope, CatalogPricingPreviewModels.Request request) {
		Objects.requireNonNull(scope, "Catalog scope is required");
		Objects.requireNonNull(request, "Pricing preview request is required");
		authorization.require(CatalogPermissions.READ);
		Instant asOf = request.asOf() == null ? clock.instant() : request.asOf();
		Map<java.util.UUID, CatalogPricingPreviewModels.PriceContext> contexts = port.load(scope,
				request.items().stream().map(CatalogPricingPreviewModels.ItemRequest::productId).toList(), asOf).stream()
				.collect(Collectors.toMap(CatalogPricingPreviewModels.PriceContext::productId, Function.identity(), (left, right) -> left));
		return new CatalogPricingPreviewModels.Result(request.items().stream().map(item -> {
			CatalogPricingPreviewModels.PriceContext context = contexts.get(item.productId());
			if (context == null) throw new CatalogResourceNotFoundException("product");
			EffectivePricePolicy.Result result = pricing.calculate(context.basePrice(), context.currency(), item.quantity(),
					scope.clientAccountId(), scope.clientAccountSegment(), scope.buyerTier(), context.promotions(), asOf);
			BigDecimal lineBaseTotal = total(result.basePrice(), item.quantity());
			BigDecimal lineEffectiveTotal = total(result.effectivePrice(), item.quantity());
			return new CatalogPricingPreviewModels.ItemResult(item.productId(), item.quantity(), result.basePrice(),
					result.effectivePrice(), lineBaseTotal, lineEffectiveTotal, result.discountAmount(),
					context.currency(), result.appliedPromotions().stream().map(value -> new CatalogPricingPreviewModels.AppliedPromotion(
							value.id(), value.name(), value.discountType(), value.discountAmount())).toList(), asOf);
		}).toList());
	}

	private static BigDecimal total(BigDecimal amount, BigDecimal quantity) {
		return amount.multiply(quantity).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
	}
}
