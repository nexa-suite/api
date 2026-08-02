package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.CatalogPermissions;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPromotionUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogPromotionPort;
import com.nexa.api.catalogmanagement.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogmanagement.domain.model.promotion.Promotion;
import com.nexa.api.catalogmanagement.domain.model.promotion.PromotionStatus;

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
    @Override public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds) {
        authorization.require(CatalogPermissions.PROMOTION_MANAGE);
        Promotion.create(UUID.randomUUID(), Promotion.DiscountType.valueOf(discountType.strip().toUpperCase(java.util.Locale.ROOT)), discountValue, startsAt, endsAt);
        return port.create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds);
    }
    @Override public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, String idempotencyKey) {
        authorization.require(CatalogPermissions.PROMOTION_MANAGE);
        Promotion.create(UUID.randomUUID(), Promotion.DiscountType.valueOf(discountType.strip().toUpperCase(java.util.Locale.ROOT)), discountValue, startsAt, endsAt);
        return port.create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds, idempotencyKey);
    }
    @Override public CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, String idempotencyKey) {
        authorization.require(CatalogPermissions.PROMOTION_MANAGE);
        Promotion.create(UUID.randomUUID(), Promotion.DiscountType.valueOf(discountType.strip().toUpperCase(java.util.Locale.ROOT)), discountValue, startsAt, endsAt);
        return port.create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, idempotencyKey);
    }
    @Override public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description, String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, long version) {
        authorization.require(CatalogPermissions.PROMOTION_MANAGE);
        return updateDomainAndPersist(scope, id, discountType, discountValue, startsAt, endsAt, () -> port.update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds, version));
    }
    @Override public CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description, String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, long version) {
        authorization.require(CatalogPermissions.PROMOTION_MANAGE);
        return updateDomainAndPersist(scope, id, discountType, discountValue, startsAt, endsAt, () -> port.update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, version));
    }
    @Override public CatalogManagementModels.PromotionView changeStatus(CatalogScope scope, UUID id, String status, long version) {
        authorization.require(CatalogPermissions.PROMOTION_MANAGE);
        CatalogManagementModels.PromotionView current = port.promotion(scope, id);
        Promotion promotion = Promotion.restore(UUID.fromString(current.id()), Promotion.DiscountType.valueOf(current.discountType()), current.discountValue(), current.startsAt(), current.endsAt(), PromotionStatus.valueOf(current.status()));
        PromotionStatus target = PromotionStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
        transition(promotion, target);
        return port.changeStatus(scope, id, target.name(), version);
    }

    private CatalogManagementModels.PromotionView updateDomainAndPersist(CatalogScope scope, UUID id, String discountType, BigDecimal discountValue,
            Instant startsAt, Instant endsAt, java.util.function.Supplier<CatalogManagementModels.PromotionView> persistence) {
        CatalogManagementModels.PromotionView current = port.promotion(scope, id);
        Promotion.restore(UUID.fromString(current.id()), Promotion.DiscountType.valueOf(discountType.strip().toUpperCase(java.util.Locale.ROOT)), discountValue, startsAt, endsAt, PromotionStatus.valueOf(current.status()));
        return persistence.get();
    }

    private static void transition(Promotion promotion, PromotionStatus target) {
        if (promotion.status() == target) return;
        switch (target) {
            case SCHEDULED -> promotion.schedule();
            case ACTIVE -> promotion.activate();
            case PAUSED -> promotion.pause();
            case EXPIRED -> promotion.expire();
            case CANCELLED -> promotion.cancel();
            case DRAFT -> throw new IllegalStateException("Promotion cannot return to DRAFT");
        }
    }
}
