package com.nexa.api.catalogcommercialpolicy.application.port.in;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CatalogPromotionUseCase {
    CatalogManagementModels.Page<CatalogManagementModels.PromotionView> promotions(CatalogScope scope, int page, int size, String status);
    CatalogManagementModels.PromotionView promotion(CatalogScope scope, UUID id);
    CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType,
            BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity,
            String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds);
    default CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType,
            BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity,
            String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, String idempotencyKey) {
        return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity, stackingPolicy, productIds, categoryIds);
    }
    default CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType,
            BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity,
            String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, String idempotencyKey, int priority) {
        return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
                minimumQuantity, stackingPolicy, productIds, categoryIds, idempotencyKey);
    }
    default CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType,
            BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity,
            String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, List<UUID> clientAccountIds,
            List<CatalogManagementModels.PromotionRuleView> rules, String idempotencyKey) {
        return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
                minimumQuantity, stackingPolicy, productIds, categoryIds, idempotencyKey);
    }
    default CatalogManagementModels.PromotionView create(CatalogScope scope, String slug, String name, String description, String discountType,
            BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity,
            String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, List<UUID> clientAccountIds,
            List<CatalogManagementModels.PromotionRuleView> rules, String idempotencyKey, int priority) {
        return create(scope, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
                minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, idempotencyKey);
    }
    CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds, long version);
    default CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
            long version, int priority) {
        return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
                minimumQuantity, stackingPolicy, productIds, categoryIds, version);
    }
    default CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
            List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, long version) {
        return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
                minimumQuantity, stackingPolicy, productIds, categoryIds, version);
    }
    default CatalogManagementModels.PromotionView update(CatalogScope scope, UUID id, String slug, String name, String description,
            String discountType, BigDecimal discountValue, String currency, Instant startsAt, Instant endsAt,
            BigDecimal minimumQuantity, String stackingPolicy, List<UUID> productIds, List<UUID> categoryIds,
            List<UUID> clientAccountIds, List<CatalogManagementModels.PromotionRuleView> rules, long version, int priority) {
        return update(scope, id, slug, name, description, discountType, discountValue, currency, startsAt, endsAt,
                minimumQuantity, stackingPolicy, productIds, categoryIds, clientAccountIds, rules, version);
    }
    CatalogManagementModels.PromotionView changeStatus(CatalogScope scope, UUID id, String status, long version);
}
