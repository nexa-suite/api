package com.nexa.api.catalogcommercialpolicy.application.port.out;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingPreviewModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.domain.model.pricing.PromotionCandidate;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

/** Persistence/query seam for the later quantity-preview adapter. */
public interface CatalogPricingPreviewPort {
	List<CatalogPricingPreviewModels.PriceContext> load(CatalogScope scope, List<UUID> productIds, Instant asOf);
}
