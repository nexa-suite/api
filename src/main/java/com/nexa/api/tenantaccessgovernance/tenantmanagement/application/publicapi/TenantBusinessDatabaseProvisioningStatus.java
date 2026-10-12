package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Non-sensitive operator view of one durable Tenant database provisioning task. */
public record TenantBusinessDatabaseProvisioningStatus(UUID tenantId, UUID workspaceId, String status,
		int attemptCount, Instant updatedAt, String registryLifecycleState) {
    public TenantBusinessDatabaseProvisioningStatus {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(workspaceId, "Workspace id is required");
        Objects.requireNonNull(status, "Provisioning status is required");
        Objects.requireNonNull(updatedAt, "Provisioning update time is required");
        if (!SetOfStatuses.contains(status) || attemptCount < 0) {
            throw new IllegalArgumentException("Tenant database provisioning status is invalid");
        }
		if (!SetOfStatuses.containsLifecycle(registryLifecycleState)) {
			throw new IllegalArgumentException("Tenant database registry lifecycle state is invalid");
		}
    }

    private static final class SetOfStatuses {
        private static boolean contains(String status) {
            return "PENDING".equals(status) || "LEASED".equals(status)
                    || "FAILED".equals(status) || "READY".equals(status);
        }
		private static boolean containsLifecycle(String status) {
			return "UNBOUND".equals(status) || "PROVISIONING".equals(status) || "READY".equals(status)
					|| "SUSPENDED".equals(status) || "FAILED".equals(status);
		}
    }
}
