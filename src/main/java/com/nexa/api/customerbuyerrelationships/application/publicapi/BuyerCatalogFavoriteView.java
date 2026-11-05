package com.nexa.api.customerbuyerrelationships.application.publicapi;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A saved Buyer preference paired with its current Catalog offer when it remains Buyer-visible. */
@org.springframework.modulith.NamedInterface("buyer-catalog-favorites")
public record BuyerCatalogFavoriteView(UUID sellableSkuId, Instant favoritedAt, CatalogItemDetail currentOffer) {
    public BuyerCatalogFavoriteView {
        sellableSkuId = Objects.requireNonNull(sellableSkuId, "Sellable SKU id is required");
        favoritedAt = Objects.requireNonNull(favoritedAt, "Favorite timestamp is required");
        if (currentOffer != null && !sellableSkuId.toString().equals(currentOffer.sellableSkuId())) {
            throw new IllegalArgumentException("Favorite SKU must match its current Catalog offer");
        }
    }
}
