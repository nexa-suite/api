package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.CatalogPermissions;
import com.nexa.api.catalogmanagement.application.model.CatalogSkuModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.port.in.CatalogSkuUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogSkuPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.Money;
import com.nexa.api.catalogmanagement.domain.model.pricing.PricePeriod;
import com.nexa.api.catalogmanagement.domain.model.productfamily.ProductFamily;
import com.nexa.api.catalogmanagement.domain.model.productfamily.ProductFamilyStatus;
import com.nexa.api.catalogmanagement.domain.model.sellablesku.SellableSku;
import com.nexa.api.catalogmanagement.domain.model.sellablesku.SellableSkuStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Application orchestration for the canonical Product Family/Sellable SKU boundary. */
public final class CatalogSkuServiceFacade implements CatalogSkuUseCase {
    private final CatalogSkuPort port;
    private final CatalogAuthorizationPort authorization;
    private final Clock clock;

    public CatalogSkuServiceFacade(CatalogSkuPort port, CatalogAuthorizationPort authorization, Clock clock) {
        this.port = Objects.requireNonNull(port, "Catalog SKU port is required");
        this.authorization = Objects.requireNonNull(authorization, "Catalog authorization port is required");
        this.clock = Objects.requireNonNull(clock, "Catalog SKU clock is required");
    }

    @Override
    public CatalogSkuModels.Page<CatalogSkuModels.FamilyView> families(CatalogScope scope, int page, int size, String search) {
        authorization.require(CatalogPermissions.READ);
        return port.families(scope, page, size, search);
    }

    @Override
    public CatalogSkuModels.FamilyView family(CatalogScope scope, UUID id) {
        authorization.require(CatalogPermissions.READ);
        return port.family(scope, id);
    }

    @Override
    public CatalogSkuModels.FamilyView createFamily(CatalogScope scope, String code, String name, String description,
            UUID categoryId, UUID brandId, String country, String manufacturer, String supplier, String storageFamily) {
        authorization.require(CatalogPermissions.MANAGE);
        ProductFamily family = ProductFamily.create(scope.tenantId(), scope.workspaceId(), code, name, description,
                categoryId, brandId, country, manufacturer, supplier, storageFamily, clock.instant());
        return port.insertFamily(scope, family);
    }

    @Override
    public CatalogSkuModels.FamilyView changeFamilyStatus(CatalogScope scope, UUID id, String status, long version) {
        authorization.require(CatalogPermissions.MANAGE);
        CatalogSkuModels.FamilyView current = port.family(scope, id);
        ProductFamily family = ProductFamily.restore(UUID.fromString(current.id()), scope.tenantId(), scope.workspaceId(),
                current.code(), current.name(), current.description(), UUID.fromString(current.categoryId()),
                UUID.fromString(current.brandId()), current.countryOfOrigin(), current.manufacturerReference(),
                current.supplierReference(), current.storageFamily(), ProductFamilyStatus.valueOf(current.status()),
                current.version());
        ProductFamilyStatus target = familyStatus(status);
        if (target == family.status()) return current;
        switch (target) {
            case ACTIVE -> family.activate(version);
            case INACTIVE -> family.deactivate(version);
            case ARCHIVED -> family.archive(version);
            case DRAFT -> throw new IllegalArgumentException("Family cannot return to DRAFT");
        }
        return port.changeFamilyStatus(scope, id, target.name(), version);
    }

    @Override
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, int page, int size, String search, UUID familyId) {
        authorization.require(CatalogPermissions.READ);
        return port.skus(scope, page, size, search, familyId);
    }

    @Override
    public CatalogSkuModels.SkuView sku(CatalogScope scope, UUID id) {
        authorization.require(CatalogPermissions.READ);
        return port.sku(scope, id);
    }

    @Override
    public CatalogSkuModels.SkuView createSku(CatalogScope scope, UUID familyId, String skuCode, String gtin,
            String presentation, String packaging, String unit, BigDecimal netWeight, BigDecimal grossWeight,
            BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax, int shelfLifeDays,
            int minimumRemainingShelfLifeDays, boolean lotTracking, boolean expiryTracking, String taxCategory) {
        authorization.require(CatalogPermissions.MANAGE);
        port.family(scope, familyId);
        SellableSku sku = SellableSku.create(scope.tenantId(), scope.workspaceId(), familyId, skuCode, gtin,
                presentation, packaging, unit, netWeight, grossWeight,
                packQuantity == null ? BigDecimal.ONE : packQuantity, temperatureMin, temperatureMax, shelfLifeDays,
                minimumRemainingShelfLifeDays, lotTracking, expiryTracking,
                taxCategory == null ? "STANDARD" : taxCategory, clock.instant());
        return port.insertSku(scope, sku);
    }

    @Override
    public CatalogSkuModels.SkuView changeSkuStatus(CatalogScope scope, UUID id, String status, long version) {
        authorization.require(CatalogPermissions.MANAGE);
        CatalogSkuModels.SkuView current = port.sku(scope, id);
        SellableSku sku = SellableSku.restore(UUID.fromString(current.id()), scope.tenantId(), scope.workspaceId(),
                UUID.fromString(current.familyId()), current.skuCode(), current.gtin(), current.presentation(),
                current.packagingType(), current.unitOfMeasure(), current.netWeight(), current.grossWeight(),
                current.packQuantity(), current.temperatureMin(), current.temperatureMax(), current.shelfLifeDays(),
                current.minimumRemainingShelfLifeDays(), current.lotTrackingRequired(), current.expiryTrackingRequired(),
                current.taxCategory(), SellableSkuStatus.valueOf(current.status()), current.visible(), current.version());
        SellableSkuStatus target = skuStatus(status);
        if (target == sku.status()) return current;
        switch (target) {
            case ACTIVE -> sku.activate(version);
            case INACTIVE -> sku.deactivate(version);
            case DISCONTINUED -> sku.discontinue(version);
            case ARCHIVED -> sku.archive(version);
            case DRAFT -> throw new IllegalArgumentException("SKU cannot return to DRAFT");
        }
        return port.changeSkuStatus(scope, id, target.name(), version);
    }

    @Override
    public CatalogSkuModels.PriceView createPrice(CatalogScope scope, UUID skuId, BigDecimal amount, String currency,
            Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription, String idempotencyKey) {
        authorization.require(CatalogPermissions.PRICE_MANAGE);
        port.sku(scope, skuId);
        Money money = Money.from(amount, currency == null ? null : currency.strip().toUpperCase(Locale.ROOT));
        PricePeriod period = new PricePeriod(validFrom == null ? clock.instant() : validFrom, validUntil);
        return port.createPrice(scope, skuId, money.amount(), money.currencyCode(), period.validFrom(), period.validUntil(),
                sourceCode, sourceDescription, idempotencyKey);
    }

    @Override
    public List<CatalogSkuModels.PriceView> prices(CatalogScope scope, UUID skuId) {
        authorization.require(CatalogPermissions.READ);
        return port.prices(scope, skuId);
    }

    private static ProductFamilyStatus familyStatus(String value) {
        try {
            return ProductFamilyStatus.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Family status is invalid", exception);
        }
    }

    private static SellableSkuStatus skuStatus(String value) {
        try {
            return SellableSkuStatus.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("SKU status is invalid", exception);
        }
    }
}
