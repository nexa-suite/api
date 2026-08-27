package com.nexa.api.catalogcommercialpolicy.application.port.out;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSkuModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogVariantModels;

import java.util.UUID;

public interface CatalogVariantPort {
    CatalogVariantModels.Page<CatalogVariantModels.VariantView> variants(CatalogScope scope, UUID familyId, int page, int size, String search);
    CatalogVariantModels.VariantView variant(CatalogScope scope, UUID id);
    CatalogVariantModels.VariantView insert(CatalogScope scope, UUID familyId, String code, String name, String description);
    CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, UUID variantId, int page, int size, String search);
}
