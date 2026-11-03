package com.nexa.api.customerbuyerrelationships.presentation.buyerfavorites.response;

import java.util.List;

/** Page metadata and items represent stored Buyer preferences; an inactive or hidden offer may be null. */
public record BuyerCatalogFavoritePageResponse(List<BuyerCatalogFavoriteResponse> items, int page, int size,
                                               long totalItems, int totalPages) {
    public BuyerCatalogFavoritePageResponse {
        items = List.copyOf(items);
    }
}
