package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import java.math.BigDecimal;

/**
 * Persistence-only catalog import data. This record deliberately omits stock
 * and identifiers that are not confirmed by the approved catalog reference.
 */
public record CatalogPersistenceSeedItemRecord(
        String catalogItemId,
        String productId,
        String itemName,
        String brandName,
        String categoryName,
        String description,
        BigDecimal unitPriceAmount,
        String unitPriceCurrency,
        String coldChainRequirement,
        String imageUrl,
        String imageFileName,
        String presentation,
        String sourcePriceCode,
        String sourcePriceDescription,
        boolean buyerVisible,
        boolean provisionalReference) {
}
