package com.nexa.api.notifications.application.port.out;

import java.util.UUID;

/** Verifies that durable push persistence remains inside its bound transaction scope. */
@FunctionalInterface
public interface PushTransactionScopeGuard {
    void verify(UUID tenantId, UUID workspaceId);
}
