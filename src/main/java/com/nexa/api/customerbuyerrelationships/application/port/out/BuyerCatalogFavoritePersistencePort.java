package com.nexa.api.customerbuyerrelationships.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence for a Buyer membership's association to sellable Catalog offers. */
@org.springframework.modulith.NamedInterface(value = "tenant-database-binding", propagate = false)
public interface BuyerCatalogFavoritePersistencePort {
    List<StoredFavorite> findPage(UUID tenantId, UUID workspaceId, UUID buyerMembershipId, int limit, long offset);

    long count(UUID tenantId, UUID workspaceId, UUID buyerMembershipId);

    default boolean contains(UUID tenantId, UUID workspaceId, UUID buyerMembershipId, UUID sellableSkuId) {
        throw new UnsupportedOperationException("Favorite status persistence is unavailable");
    }

    Instant addIfAbsent(UUID tenantId, UUID workspaceId, UUID buyerMembershipId, UUID sellableSkuId);

    void remove(UUID tenantId, UUID workspaceId, UUID buyerMembershipId, UUID sellableSkuId);

    @org.springframework.modulith.NamedInterface(value = "tenant-database-binding", propagate = false)
    record StoredFavorite(UUID sellableSkuId, Instant createdAt) { }
}
