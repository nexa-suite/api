package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.time.LocalTime;
import java.util.Optional;

/** Owner boundary for existing warehouse operational settings. */
public interface OperationalSettingsAccess {
    Optional<Snapshot> find(String workspaceId);
    int update(String workspaceId, String selectionPolicy, LocalTime startsAt, LocalTime endsAt, long expectedVersion);
    public record Snapshot(String selectionPolicy, String orderCutoffPolicy, String fulfillmentDefaults,
                           String inventoryVisibilityPolicy, String buyerAvailabilityPolicy,
                           LocalTime startsAt, LocalTime endsAt, int orderCutoffMinutes,
                           boolean thermalLogRequired, long version) { }
}
