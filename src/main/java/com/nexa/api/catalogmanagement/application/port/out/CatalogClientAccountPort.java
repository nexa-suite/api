package com.nexa.api.catalogmanagement.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Resolves the buyer commercial account through a narrow cross-context query. */
public interface CatalogClientAccountPort {
	Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId);

	default Optional<ClientAccountProfile> findProfileForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
		return findForMembership(tenantId, workspaceId, membershipId)
				.map(id -> new ClientAccountProfile(id, null, null));
	}

	record ClientAccountProfile(UUID id, String segment, String buyerTier) { }
}
