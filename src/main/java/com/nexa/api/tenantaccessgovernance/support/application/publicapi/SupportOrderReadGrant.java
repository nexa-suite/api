package com.nexa.api.tenantaccessgovernance.support.application.publicapi;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable BC01 grant value; BC04 must validate exact persisted scope before and after each read. */
public final class SupportOrderReadGrant {
	private final UUID grantId;
	private final UUID requesterOperatorId;
	private final UUID tenantId;
	private final UUID workspaceId;
	private final UUID salesOrderId;
	private final Instant expiresAt;

	SupportOrderReadGrant(UUID grantId, UUID requesterOperatorId, UUID tenantId, UUID workspaceId,
			UUID salesOrderId, Instant expiresAt) {
		this.grantId = Objects.requireNonNull(grantId);
		this.requesterOperatorId = Objects.requireNonNull(requesterOperatorId);
		this.tenantId = Objects.requireNonNull(tenantId);
		this.workspaceId = Objects.requireNonNull(workspaceId);
		this.salesOrderId = Objects.requireNonNull(salesOrderId);
		this.expiresAt = Objects.requireNonNull(expiresAt);
	}

	public UUID grantId() { return grantId; }
	public UUID requesterOperatorId() { return requesterOperatorId; }
	public UUID tenantId() { return tenantId; }
	public UUID workspaceId() { return workspaceId; }
	public UUID salesOrderId() { return salesOrderId; }
	public Instant expiresAt() { return expiresAt; }
}
