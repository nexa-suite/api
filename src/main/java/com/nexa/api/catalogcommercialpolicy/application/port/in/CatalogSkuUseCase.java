package com.nexa.api.catalogcommercialpolicy.application.port.in;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSkuModels;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Application boundary for Product Family, Sellable SKU and SKU pricing commands/queries. */
public interface CatalogSkuUseCase {
    CatalogSkuModels.Page<CatalogSkuModels.FamilyView> families(CatalogScope scope, int page, int size, String search);
    CatalogSkuModels.FamilyView family(CatalogScope scope, UUID id);
    CatalogSkuModels.FamilyView createFamily(CatalogScope scope, String code, String name, String description,
            UUID categoryId, UUID brandId, String country, String manufacturer, String supplier, String storageFamily);
    CatalogSkuModels.FamilyView changeFamilyStatus(CatalogScope scope, UUID id, String status, long version);
    CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, int page, int size, String search, UUID familyId);
    CatalogSkuModels.SkuView sku(CatalogScope scope, UUID id);
    CatalogSkuModels.SkuView createSku(CatalogScope scope, UUID familyId, String skuCode, String gtin,
            String presentation, String packaging, String unit, BigDecimal netWeight, BigDecimal grossWeight,
            BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax, int shelfLifeDays,
            int minimumRemainingShelfLifeDays, boolean lotTracking, boolean expiryTracking, String taxCategory);
    CatalogSkuModels.SkuView changeSkuStatus(CatalogScope scope, UUID id, String status, long version);
    CatalogSkuModels.PriceView createPrice(CatalogScope scope, UUID skuId, BigDecimal amount, String currency,
            Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription, String idempotencyKey);
    List<CatalogSkuModels.PriceView> prices(CatalogScope scope, UUID skuId);
}
