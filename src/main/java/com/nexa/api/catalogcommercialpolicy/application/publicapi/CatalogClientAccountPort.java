package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Resolves buyer account facts through an owner-provided customer query. */
public interface CatalogClientAccountPort {
    Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId);

    Optional<ClientAccountProfile> findProfileForMembership(UUID tenantId, UUID workspaceId, UUID membershipId);

    Optional<ClientAccountProfile> findActiveProfile(UUID tenantId, UUID workspaceId, UUID customerAccountId);

    record ClientAccountProfile(UUID id, String segment, String buyerTier) {
        public ClientAccountProfile {
            id = Objects.requireNonNull(id, "Client account id is required");
        }
    }
}
