package com.nexa.api.customerbuyerrelationships.application.publicapi;

import java.util.List;
import java.util.UUID;

/** Published customer-relationship lookup for membership links attached to an account. */
public interface CustomerMembershipQuery {
    List<UUID> findMembershipIds(UUID tenantId, UUID workspaceId, UUID clientAccountId);
}
