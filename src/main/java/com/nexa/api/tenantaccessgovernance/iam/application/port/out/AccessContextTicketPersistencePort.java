package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketRecord;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

import java.time.Instant;
import java.util.Optional;

public interface AccessContextTicketPersistencePort {
	void create(AccessContextTicketRecord ticket);

	Optional<AccessContextTicketRecord> findByHash(String ticketHash);

	/** Must be called inside the context-selection transaction and lock the ticket row until commit. */
	Optional<AccessContextTicketRecord> findByHashForUpdate(String ticketHash);

	/** Conditional consumption protects the invariant if a persistence adapter changes its lock strategy. */
	boolean consume(String ticketHash, Instant consumedAt);

	/** Invalidates all outstanding pre-context credentials after the identity's password changes. */
	int invalidatePendingForUser(UserAccountId userAccountId, Instant revokedAt);
}
