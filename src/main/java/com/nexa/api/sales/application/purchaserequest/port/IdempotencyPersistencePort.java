package com.nexa.api.sales.application.purchaserequest.port;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyPersistencePort {
	Optional<IdempotencyResult> find(String tenantId, String workspaceId, String actorMembershipId, String operation, String key);
	void save(String tenantId, String workspaceId, String actorMembershipId, String operation, String key, String resourceId, long responseVersion, UUID id, long nowEpochMillis);
	record IdempotencyResult(String resourceId, long responseVersion) { }
}
