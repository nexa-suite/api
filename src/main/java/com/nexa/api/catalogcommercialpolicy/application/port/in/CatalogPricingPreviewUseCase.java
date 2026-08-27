package com.nexa.api.catalogcommercialpolicy.application.port.in;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingPreviewModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

/** Application contract for server-side, quantity-aware price previews. */
public interface CatalogPricingPreviewUseCase {
	CatalogPricingPreviewModels.Result preview(CatalogScope scope, CatalogPricingPreviewModels.Request request);
}
