package com.nexa.api.catalogmanagement.application.port.out;

import com.nexa.api.catalogmanagement.application.model.CatalogPricingPreviewModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.domain.model.pricing.PromotionCandidate;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

/** Persistence/query seam for the later quantity-preview adapter. */
public interface CatalogPricingPreviewPort {
	List<CatalogPricingPreviewModels.PriceContext> load(CatalogScope scope, List<UUID> productIds, Instant asOf);
}
