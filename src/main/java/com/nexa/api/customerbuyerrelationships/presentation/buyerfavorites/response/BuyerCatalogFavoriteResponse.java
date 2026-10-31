package com.nexa.api.customerbuyerrelationships.presentation.buyerfavorites.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Transport representation of a Buyer membership's saved SKU and its current offer view. */
public record BuyerCatalogFavoriteResponse(UUID sellableSkuId, Instant favoritedAt,
        @Schema(description = "Current Buyer-visible offer. Null when the saved SKU is no longer active or visible.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        CurrentCatalogOfferResponse currentOffer) { }
