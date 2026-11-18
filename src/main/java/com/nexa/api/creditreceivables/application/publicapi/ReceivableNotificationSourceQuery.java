package com.nexa.api.creditreceivables.application.publicapi;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped account identity needed to project a receivable notification. */
@FunctionalInterface
public interface ReceivableNotificationSourceQuery {
    Optional<UUID> findClientAccountId(UUID tenantId, UUID workspaceId, UUID receivableId);
}
