package com.nexa.api.inventoryavailability.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Narrow command boundary for the accepted canonical outbox workflow. */
public interface InventoryReservationCommands {
    void reserve(CurrentAccessContext context, String orderId, long expectedVersion, String key, String correlation);
}
