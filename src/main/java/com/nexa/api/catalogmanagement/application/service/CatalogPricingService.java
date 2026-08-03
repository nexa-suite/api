package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.CatalogPermissions;
import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPricingUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPricingPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.Money;
import com.nexa.api.catalogmanagement.domain.model.pricing.PricePeriod;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class CatalogPricingService implements CatalogPricingUseCase {
	private final CatalogPricingPort port;
	private final CatalogAuthorizationPort authorization;
	private final Clock clock;

	public CatalogPricingService(CatalogPricingPort port, CatalogAuthorizationPort authorization) {
		this(port, authorization, Clock.systemUTC());
	}

	public CatalogPricingService(CatalogPricingPort port, CatalogAuthorizationPort authorization, Clock clock) {
		this.port = Objects.requireNonNull(port, "Catalog pricing port is required");
		this.authorization = Objects.requireNonNull(authorization, "Catalog authorization port is required");
		this.clock = Objects.requireNonNull(clock, "Catalog pricing clock is required");
	}

	@Override
	public List<CatalogManagementModels.PriceView> history(CatalogScope scope, UUID productId) {
		authorization.require(CatalogPermissions.READ);
		return port.history(scope, productId);
	}

	@Override
	public CatalogManagementModels.PriceView create(CatalogScope scope, UUID productId, BigDecimal amount, String currency,
			Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription) {
		return create(scope, productId, amount, currency, validFrom, validUntil, sourceCode, sourceDescription, null);
	}

	@Override
	public CatalogManagementModels.PriceView create(CatalogScope scope, UUID productId, BigDecimal amount, String currency,
			Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription, String idempotencyKey) {
		authorization.require(CatalogPermissions.PRICE_MANAGE);
		Money money = Money.from(amount, currency == null ? null : currency.strip().toUpperCase(Locale.ROOT));
		PricePeriod period = new PricePeriod(validFrom == null ? clock.instant() : validFrom, validUntil);
		return port.create(scope, productId, money.amount(), money.currencyCode(), period.validFrom(), period.validUntil(),
				sourceCode, sourceDescription, idempotencyKey);
	}

	@Override
	public CatalogManagementModels.PriceView cancel(CatalogScope scope, UUID priceId, long version) {
		authorization.require(CatalogPermissions.PRICE_MANAGE);
		return port.cancel(scope, priceId, version);
	}
}
