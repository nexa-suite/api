package com.nexa.api.tenantmanagement.domain.model.invitation;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public record InvitationExpiry(Instant value) {
	public InvitationExpiry { Objects.requireNonNull(value, "Invitation expiry is required"); }
	public boolean hasExpired(Clock clock) { return !value.isAfter(Objects.requireNonNull(clock).instant()); }
}
