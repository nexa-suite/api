package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.CatalogPermissions;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPromotionUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPromotionPort;
import com.nexa.api.catalogmanagement.application.exception.CatalogResourceNotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogPromotionService implements CatalogPromotionUseCase {
    private final CatalogPromotionPort port;
    private final CatalogAuthorizationPort authorization;
    public CatalogPromotionService(CatalogPromotionPort port, CatalogAuthorizationPort authorization) { this.port = port; this.authorization = authorization; }
    @Override public CatalogManagementModels.Page<CatalogManagementModels.PromotionView> promotions(CatalogScope scope, int page, int size, String status) { authorization.require(CatalogPermissions.PROMOTION_READ); return port.promotions(scope, page, size, status); }
    @Override public CatalogManagementModels.PromotionView promotion(CatalogScope scope, UUID id) { authorization.require(CatalogPermissions.PROMOTION_READ); return port.promotion(scope, id); }
    @Override public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds) { authorization.require(CatalogPermissions.PROMOTION_MANAGE); return port.create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds); }
    @Override public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, String idempotencyKey) { authorization.require(CatalogPermissions.PROMOTION_MANAGE); return port.create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds, idempotencyKey); }
    @Override public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description, String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, long version) { authorization.require(CatalogPermissions.PROMOTION_MANAGE); return port.update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds, version); }
    @Override public CatalogManagementModels.PromotionView changeStatus(CatalogScope scope, UUID id, String status, long version) { authorization.require(CatalogPermissions.PROMOTION_MANAGE); return port.changeStatus(scope, id, status, version); }
}
