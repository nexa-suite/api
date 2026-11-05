package com.nexa.api.fulfillmentdelivery.application.model;

import java.util.Set;
import java.util.UUID;

/** Server-resolved account and Warehouse scopes for one Sales membership. */
public record DispatchSalesOwnerScope(Set<UUID> activeClientAccountIds, Set<UUID> activeWarehouseIds) {
    public DispatchSalesOwnerScope(Set<UUID> activeClientAccountIds) {
        this(activeClientAccountIds, Set.of());
    }

    public DispatchSalesOwnerScope {
        activeClientAccountIds = activeClientAccountIds == null ? Set.of() : Set.copyOf(activeClientAccountIds);
        activeWarehouseIds = activeWarehouseIds == null ? Set.of() : Set.copyOf(activeWarehouseIds);
    }
}
