package com.nexa.api.sales.application.purchaserequest.port;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyPersistencePort {
	Optional<IdempotencyResult> find(String tenantId, String workspaceId, String actorMembershipId, String operation, String key);
	default void lock(String tenantId, String workspaceId, String actorMembershipId, String operation, String key) { }
	default Optional<IdempotencyResult> find(String tenantId, String workspaceId, String actorMembershipId, String operation, String key, String requestHash) {
		return find(tenantId, workspaceId, actorMembershipId, operation, key);
	}
	void save(String tenantId, String workspaceId, String actorMembershipId, String operation, String key, String resourceId, long responseVersion, UUID id, long nowEpochMillis);
	default void save(String tenantId, String workspaceId, String actorMembershipId, String operation, String key, String resourceId, long responseVersion, UUID id, long nowEpochMillis, String requestHash) {
		save(tenantId, workspaceId, actorMembershipId, operation, key, resourceId, responseVersion, id, nowEpochMillis);
	}
	default void save(String tenantId, String workspaceId, String actorMembershipId, String operation, String key,
			String resourceId, long responseVersion, UUID id, long nowEpochMillis, String requestHash, String responseJson) {
		save(tenantId, workspaceId, actorMembershipId, operation, key, resourceId, responseVersion, id, nowEpochMillis, requestHash);
	}
	default void updateResponse(String tenantId, String workspaceId, String actorMembershipId, String operation,
			String key, String responseJson) { }
	record IdempotencyResult(String resourceId, long responseVersion, String responseJson) {
		public IdempotencyResult(String resourceId, long responseVersion) { this(resourceId, responseVersion, null); }
	}
}
