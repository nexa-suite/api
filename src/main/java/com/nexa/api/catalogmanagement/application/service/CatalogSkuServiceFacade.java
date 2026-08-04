package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.model.CatalogSkuModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.out.CatalogSkuPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CatalogSkuServiceFacade {
    private final CatalogSkuPort port;
    public CatalogSkuServiceFacade(CatalogSkuPort port) { this.port = port; }
    public CatalogSkuModels.Page<CatalogSkuModels.FamilyView> families(CatalogScope scope, int page, int size, String search) { return port.families(scope, page, size, search); }
    public CatalogSkuModels.FamilyView family(CatalogScope scope, UUID id) { return port.family(scope, id); }
    public CatalogSkuModels.FamilyView createFamily(CatalogScope scope, String code, String name, String description, UUID categoryId, UUID brandId, String country, String manufacturer, String supplier, String storageFamily) { return port.createFamily(scope, code, name, description, categoryId, brandId, country, manufacturer, supplier, storageFamily); }
    public CatalogSkuModels.FamilyView changeFamilyStatus(CatalogScope scope, UUID id, String status, long version) { return port.changeFamilyStatus(scope, id, status, version); }
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, int page, int size, String search, UUID familyId) { return port.skus(scope, page, size, search, familyId); }
    public CatalogSkuModels.SkuView sku(CatalogScope scope, UUID id) { return port.sku(scope, id); }
    public CatalogSkuModels.SkuView createSku(CatalogScope scope, UUID familyId, String skuCode, String gtin, String presentation, String packaging, String unit, BigDecimal netWeight, BigDecimal grossWeight, BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax, int shelfLifeDays, int minimumRemainingShelfLifeDays, boolean lotTracking, boolean expiryTracking, String taxCategory) { return port.createSku(scope, familyId, skuCode, gtin, presentation, packaging, unit, netWeight, grossWeight, packQuantity, temperatureMin, temperatureMax, shelfLifeDays, minimumRemainingShelfLifeDays, lotTracking, expiryTracking, taxCategory); }
    public CatalogSkuModels.SkuView changeSkuStatus(CatalogScope scope, UUID id, String status, long version) { return port.changeSkuStatus(scope, id, status, version); }
    public CatalogSkuModels.PriceView createPrice(CatalogScope scope, UUID skuId, BigDecimal amount, String currency, Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription) { return port.createPrice(scope, skuId, amount, currency, validFrom, validUntil, sourceCode, sourceDescription); }
    public List<CatalogSkuModels.PriceView> prices(CatalogScope scope, UUID skuId) { return port.prices(scope, skuId); }
}
