package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessContextTicketRecord;

import java.time.Instant;
import java.util.Optional;

public interface AccessContextTicketPersistencePort {
	void create(AccessContextTicketRecord ticket);

	Optional<AccessContextTicketRecord> findByHash(String ticketHash);

	/** Must be called inside the context-selection transaction and lock the ticket row until commit. */
	Optional<AccessContextTicketRecord> findByHashForUpdate(String ticketHash);

	/** Conditional consumption protects the invariant if a persistence adapter changes its lock strategy. */
	boolean consume(String ticketHash, Instant consumedAt);
}
