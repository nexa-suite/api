package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A tenant-owned authority grant connecting an active membership to a BC-05 Warehouse. */
public record WarehouseAccessGrant(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId,
                                   UUID warehouseId, WarehouseAccessGrantStatus status, long version,
                                   MembershipId changedBy, Instant changedAt) {
    public WarehouseAccessGrant {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(workspaceId, "Workspace id is required");
        Objects.requireNonNull(membershipId, "Membership id is required");
        Objects.requireNonNull(warehouseId, "Warehouse id is required");
        Objects.requireNonNull(status, "Grant status is required");
        Objects.requireNonNull(changedBy, "Changing membership id is required");
        Objects.requireNonNull(changedAt, "Change time is required");
        if (version < 0) throw new IllegalArgumentException("Grant version cannot be negative");
    }

    public WarehouseAccessGrant activate(long expectedVersion, MembershipId actor, Instant changed) {
        requireVersion(expectedVersion);
        return new WarehouseAccessGrant(tenantId, workspaceId, membershipId, warehouseId,
                WarehouseAccessGrantStatus.ACTIVE, version + 1, actor, changed);
    }

    public WarehouseAccessGrant revoke(long expectedVersion, MembershipId actor, Instant changed) {
        requireVersion(expectedVersion);
        return new WarehouseAccessGrant(tenantId, workspaceId, membershipId, warehouseId,
                WarehouseAccessGrantStatus.REVOKED, version + 1, actor, changed);
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) throw new IllegalStateException("Warehouse access grant version is stale");
    }
}
