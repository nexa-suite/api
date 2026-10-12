package com.nexa.api.notifications.application.publicapi;

import java.util.Set;
import java.util.UUID;

/** Resolves current, authorized notification recipients from central Tenant-owned sources. */
@org.springframework.modulith.NamedInterface(value = "notification-projections", propagate = false)
public interface NotificationRecipientPreflightPort {
    Set<UUID> findEligibleMembershipIds(UUID tenantId, UUID workspaceId, String eventType,
            String aggregateType, UUID aggregateId, UUID clientAccountId, Set<UUID> tenantBuyerMembershipIds);
}
