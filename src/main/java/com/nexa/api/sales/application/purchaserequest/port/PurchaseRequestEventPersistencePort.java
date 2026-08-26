package com.nexa.api.sales.application.purchaserequest.port;

import java.util.UUID;
import java.util.Map;

public interface PurchaseRequestEventPersistencePort {
	void append(UUID eventId, String purchaseRequestId, String tenantId, String workspaceId, String actorMembershipId,
			String eventType, String fromStatus, String toStatus, long nowEpochMillis);

	default void appendCanonical(String eventType, String purchaseRequestId, String tenantId, String workspaceId,
			String correlationId, UUID causationId, Map<String, Object> payload, long nowEpochMillis) { }

	default void appendCanonical(String eventType, String purchaseRequestId, String tenantId, String workspaceId,
			String correlationId, UUID causationId, String occurrenceKey, Map<String, Object> payload, long nowEpochMillis) {
		appendCanonical(eventType, purchaseRequestId, tenantId, workspaceId, correlationId, causationId, payload, nowEpochMillis);
	}
}
