package com.nexa.api.catalogcommercialpolicy.application.port.out;

import java.util.Optional;
import java.util.UUID;
import java.util.Locale;
import java.util.Objects;

/** Resolves the buyer commercial account through a narrow cross-context query. */
public interface CatalogClientAccountPort {
	Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId);

	default Optional<ClientAccountProfile> findProfileForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
		return findForMembership(tenantId, workspaceId, membershipId)
				.map(id -> new ClientAccountProfile(id, null, null));
	}

	record ClientAccountProfile(UUID id, String segment, String buyerTier) {
		public ClientAccountProfile {
			id = Objects.requireNonNull(id, "Client account id is required");
			segment = normalize(segment);
			buyerTier = normalize(buyerTier);
		}

		private static String normalize(String value) {
			if (value == null || value.isBlank()) return null;
			return value.strip().toUpperCase(Locale.ROOT);
		}
	}
}
