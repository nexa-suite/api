package com.nexa.api.catalogmanagement.application.port.out;

import com.nexa.api.catalogmanagement.application.model.CatalogSkuModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.domain.model.productfamily.ProductFamily;
import com.nexa.api.catalogmanagement.domain.model.sellablesku.SellableSku;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CatalogSkuPort {
    CatalogSkuModels.Page<CatalogSkuModels.FamilyView> families(CatalogScope scope, int page, int size, String search);
    CatalogSkuModels.FamilyView family(CatalogScope scope, UUID id);
    CatalogSkuModels.FamilyView insertFamily(CatalogScope scope, ProductFamily family);
    CatalogSkuModels.FamilyView changeFamilyStatus(CatalogScope scope, UUID id, String status, long expectedVersion);
    CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, int page, int size, String search, UUID familyId);
    CatalogSkuModels.Page<CatalogSkuModels.SkuView> skusByVariant(CatalogScope scope, int page, int size, String search, UUID variantId);
    CatalogSkuModels.SkuView sku(CatalogScope scope, UUID id);
    CatalogSkuModels.SkuView insertSku(CatalogScope scope, SellableSku sku);
    CatalogSkuModels.SkuView changeSkuStatus(CatalogScope scope, UUID id, String status, long expectedVersion);
    CatalogSkuModels.PriceView createPrice(CatalogScope scope, UUID skuId, BigDecimal amount, String currency, Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription, String idempotencyKey);
    List<CatalogSkuModels.PriceView> prices(CatalogScope scope, UUID skuId);
}
