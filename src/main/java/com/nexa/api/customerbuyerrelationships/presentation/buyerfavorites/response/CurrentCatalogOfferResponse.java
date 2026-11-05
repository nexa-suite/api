package com.nexa.api.customerbuyerrelationships.presentation.buyerfavorites.response;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Buyer-safe current BC-03 offer fields; private base-price and promotion inputs are not exposed. */
public record CurrentCatalogOfferResponse(
        String catalogItemId,
        String productId,
        String itemName,
        String brandName,
        String categoryName,
        String description,
        String presentation,
        String imageUrl,
        String imageFileName,
        String status,
        String availabilityStatus,
        BigDecimal sellableAvailability,
        String productFamilyId,
        String productFamilyCode,
        String productFamilyName,
        UUID sellableSkuId,
        String skuCode,
        String unitOfMeasure,
        String packagingType,
        String productVariantCode,
        String productVariantName,
        OfferPrice currentPrice,
        Instant pricingAsOf,
        Instant availabilityAsOf) {

    public static CurrentCatalogOfferResponse from(CatalogItemDetail offer) {
        CatalogPricingView pricing = offer.pricing();
        OfferPrice currentPrice = pricing == null || pricing.effectivePrice() == null || pricing.currency() == null
                ? null : new OfferPrice(pricing.effectivePrice().toPlainString(), pricing.currency());
        return new CurrentCatalogOfferResponse(offer.catalogItemId(), offer.productId(), offer.itemName(),
                offer.brandName(), offer.categoryName(), offer.description(), offer.presentation(), offer.imageUrl(),
                offer.imageFileName(), offer.status(), offer.availabilityStatus(), offer.sellableAvailability(),
                offer.productFamilyId(), offer.productFamilyCode(), offer.productFamilyName(),
                UUID.fromString(offer.sellableSkuId()), offer.skuCode(), offer.unitOfMeasure(), offer.packagingType(),
                offer.productVariantCode(), offer.productVariantName(), currentPrice,
                pricing == null ? null : pricing.pricingAsOf(), offer.availabilityAsOf());
    }

    public record OfferPrice(String amount, String currency) { }
}
