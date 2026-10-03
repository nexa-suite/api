package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.time.Instant;
import java.util.UUID;

/** Stable cross-context projection of a Warehouse access grant. */
public record WarehouseAccessGrantView(UUID tenantId, UUID workspaceId, UUID membershipId,
                                       UUID warehouseId, String status, long version,
                                       UUID changedByMembershipId, Instant changedAt) { }
