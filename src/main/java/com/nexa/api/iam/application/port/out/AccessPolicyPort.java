package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.AccessPolicy;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;

import java.util.Optional;

public interface AccessPolicyPort {
	Optional<AccessPolicy> findFor(UserAccountId userAccountId, ClientSurface surface);

	default Optional<AccessPolicy> findFor(UserAccountId userAccountId, String workspaceSlug, ClientSurface surface) {
		return findFor(userAccountId, surface);
	}

	/** Resolves a persisted session by its exact membership instead of a user-supplied slug. */
	default Optional<AccessPolicy> findForMembership(UserAccountId userAccountId, String membershipId, ClientSurface surface) {
		return Optional.empty();
	}
}
