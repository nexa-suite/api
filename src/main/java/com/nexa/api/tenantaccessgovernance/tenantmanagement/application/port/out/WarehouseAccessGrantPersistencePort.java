package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.WarehouseAccessGrant;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Tenant Governance persistence boundary for Warehouse object grants. */
public interface WarehouseAccessGrantPersistencePort {
    boolean isActiveScope(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId);

    /** Allows current INTERNAL members and the exact persisted SYSTEM_WORKFLOW/NEXA_AUTOMATION actor. */
    boolean isActiveMembership(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId);

    Set<UUID> activeWarehouseIds(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId);

    List<WarehouseAccessGrant> findForWarehouse(TenantId tenantId, WorkspaceId workspaceId, UUID warehouseId);

    Optional<WarehouseAccessGrant> find(TenantId tenantId, WorkspaceId workspaceId,
                                        MembershipId membershipId, UUID warehouseId);

    WarehouseAccessGrant insert(WarehouseAccessGrant grant);

    int update(WarehouseAccessGrant grant, long expectedVersion);
}
