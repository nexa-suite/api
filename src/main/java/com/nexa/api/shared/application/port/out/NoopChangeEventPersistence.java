package com.nexa.api.shared.application.port.out;

public final class NoopChangeEventPersistence implements ChangeEventPersistencePort {
	@Override public void append(String tenantId, String workspaceId, String clientAccountId, String aggregateType,
			String aggregateId, String eventType, String publicStatus, long occurredAtEpochMillis) { }
}
