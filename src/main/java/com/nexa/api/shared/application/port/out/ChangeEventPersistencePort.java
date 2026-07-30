package com.nexa.api.shared.application.port.out;

/** Transactional append-only integration feed boundary. */
public interface ChangeEventPersistencePort {
	void append(String tenantId, String workspaceId, String clientAccountId, String aggregateType,
			String aggregateId, String eventType, String payload, long occurredAtEpochMillis);
}
