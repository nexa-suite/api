package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.CatalogPermissions;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPricingUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPricingPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.Money;
import com.nexa.api.catalogmanagement.domain.model.pricing.PricePeriod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogPricingService implements CatalogPricingUseCase {
    private final CatalogPricingPort port;
    private final CatalogAuthorizationPort authorization;
    public CatalogPricingService(CatalogPricingPort port, CatalogAuthorizationPort authorization) { this.port = port; this.authorization = authorization; }
    @Override public List<CatalogManagementModels.PriceView> history(CatalogScope scope, UUID productId) { authorization.require(CatalogPermissions.READ); return port.history(scope, productId); }
    @Override public CatalogManagementModels.PriceView create(CatalogScope scope, UUID productId, BigDecimal amount, String currency, Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription) {
        authorization.require(CatalogPermissions.PRICE_MANAGE);
        Money.from(amount, currency == null ? null : currency.strip().toUpperCase(java.util.Locale.ROOT));
        PricePeriod period = new PricePeriod(validFrom == null ? Instant.now() : validFrom, validUntil);
        return port.create(scope, productId, amount, currency, period.validFrom(), period.validUntil(), sourceCode, sourceDescription);
    }
    @Override public CatalogManagementModels.PriceView create(CatalogScope scope, UUID productId, BigDecimal amount, String currency, Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription, String idempotencyKey) {
        authorization.require(CatalogPermissions.PRICE_MANAGE);
        Money.from(amount, currency == null ? null : currency.strip().toUpperCase(java.util.Locale.ROOT));
        PricePeriod period = new PricePeriod(validFrom == null ? Instant.now() : validFrom, validUntil);
        return port.create(scope, productId, amount, currency, period.validFrom(), period.validUntil(), sourceCode, sourceDescription, idempotencyKey);
    }
    @Override public CatalogManagementModels.PriceView cancel(CatalogScope scope, UUID priceId, long version) {
        authorization.require(CatalogPermissions.PRICE_MANAGE);
        return port.cancel(scope, priceId, version);
    }
}
