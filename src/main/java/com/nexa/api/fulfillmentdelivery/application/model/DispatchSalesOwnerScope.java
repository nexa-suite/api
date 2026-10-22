package com.nexa.api.fulfillmentdelivery.application.model;

import java.util.Set;
import java.util.UUID;

/** Server-resolved active account IDs assigned to one Sales membership. */
public record DispatchSalesOwnerScope(Set<UUID> activeClientAccountIds) {
    public DispatchSalesOwnerScope {
        activeClientAccountIds = activeClientAccountIds == null ? Set.of() : Set.copyOf(activeClientAccountIds);
    }
}
