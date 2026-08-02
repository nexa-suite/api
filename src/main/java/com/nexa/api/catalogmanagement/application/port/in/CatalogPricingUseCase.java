package com.nexa.api.catalogmanagement.application.port.in;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CatalogPricingUseCase {
    List<CatalogManagementModels.PriceView> history(CatalogScope scope, UUID productId);
    CatalogManagementModels.PriceView create(CatalogScope scope, UUID productId, BigDecimal amount, String currency, Instant validFrom,
            Instant validUntil, String sourceCode, String sourceDescription);
    default CatalogManagementModels.PriceView create(CatalogScope scope, UUID productId, BigDecimal amount, String currency, Instant validFrom,
            Instant validUntil, String sourceCode, String sourceDescription, String idempotencyKey) { return create(scope, productId, amount, currency, validFrom, validUntil, sourceCode, sourceDescription); }
    CatalogManagementModels.PriceView cancel(CatalogScope scope, UUID priceId, long version);
}
