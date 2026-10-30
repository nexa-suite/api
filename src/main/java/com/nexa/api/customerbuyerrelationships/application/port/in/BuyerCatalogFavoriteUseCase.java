package com.nexa.api.customerbuyerrelationships.application.port.in;

import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerCatalogFavoriteView;
import com.nexa.api.customerbuyerrelationships.application.publicapi.BuyerCatalogFavoritePage;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Optional;
import java.util.UUID;

/** Membership-owned Buyer favorites; catalog offer fields are resolved when read. */
@org.springframework.modulith.NamedInterface("buyer-catalog-favorite-usecases")
public interface BuyerCatalogFavoriteUseCase {
    BuyerCatalogFavoritePage list(CurrentAccessContext context, int page, int size);

    default boolean isSaved(CurrentAccessContext context, UUID sellableSkuId) {
        throw new UnsupportedOperationException("Favorite status reads are unavailable");
    }

    Optional<BuyerCatalogFavoriteView> add(CurrentAccessContext context, UUID sellableSkuId);

    void remove(CurrentAccessContext context, UUID sellableSkuId);
}
