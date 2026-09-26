package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

import java.time.Instant;
import java.util.Objects;

/** Server-side ticket record; it contains only the digest, never the opaque bearer value. */
public record AccessContextTicketRecord(String ticketHash, UserAccountId userAccountId, ClientSurface surface,
		Instant issuedAt, Instant expiresAt, Instant consumedAt, Instant revokedAt) {
	public AccessContextTicketRecord(String ticketHash, UserAccountId userAccountId, ClientSurface surface,
			Instant issuedAt, Instant expiresAt, Instant consumedAt) {
		this(ticketHash, userAccountId, surface, issuedAt, expiresAt, consumedAt, null);
	}

	public AccessContextTicketRecord {
		if (ticketHash == null || !ticketHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Ticket digest is invalid");
		Objects.requireNonNull(userAccountId, "User account id is required");
		Objects.requireNonNull(surface, "Client surface is required");
		Objects.requireNonNull(issuedAt, "Issue time is required");
		Objects.requireNonNull(expiresAt, "Expiry time is required");
		if (!expiresAt.equals(issuedAt.plusSeconds(300))) throw new IllegalArgumentException("Ticket lifetime must be five minutes");
		if (consumedAt != null && revokedAt != null) throw new IllegalArgumentException("Ticket cannot be consumed and revoked");
	}
	public boolean isUsableAt(Instant now) {
		return consumedAt == null && revokedAt == null && expiresAt.isAfter(now);
	}

	@Override
	public String toString() {
		return "AccessContextTicketRecord[ticketHash=[REDACTED], userAccountId=" + userAccountId + ", surface="
				+ surface + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + ", consumedAt=" + consumedAt
				+ ", revokedAt=" + revokedAt + "]";
	}
}
