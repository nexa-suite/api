package com.nexa.api.sales.application.purchaserequest.port;

import java.util.UUID;

public interface PurchaseRequestEventPersistencePort {
	void append(UUID eventId, String purchaseRequestId, String tenantId, String workspaceId, String actorMembershipId,
			String eventType, String fromStatus, String toStatus, long nowEpochMillis);
}
