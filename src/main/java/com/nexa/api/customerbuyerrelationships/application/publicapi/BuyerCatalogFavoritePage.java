package com.nexa.api.customerbuyerrelationships.application.publicapi;

import java.util.List;
import java.util.Objects;

/** One page of stored Buyer preferences; hidden or inactive offers remain as entries with a null current offer. */
@org.springframework.modulith.NamedInterface("buyer-catalog-favorites")
public record BuyerCatalogFavoritePage(List<BuyerCatalogFavoriteView> items, int page, int size, long totalItems) {
    public BuyerCatalogFavoritePage {
        items = List.copyOf(Objects.requireNonNull(items, "Favorite items are required"));
        if (page < 0 || size < 1 || size > 100 || totalItems < 0) {
            throw new IllegalArgumentException("Favorite page metadata is invalid");
        }
    }
}
