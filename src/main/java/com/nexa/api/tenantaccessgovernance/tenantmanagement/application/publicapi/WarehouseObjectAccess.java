package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Current, server-evaluated BC-01 grants for BC-05 Warehouse objects. */
public interface WarehouseObjectAccess {
    /** Validates current administrator authority before BC-05 resolves Warehouse existence. */
    void authorizeAdministration(CurrentAccessContext context);

    Set<UUID> activeWarehouseIds(CurrentAccessContext context);

    boolean hasActiveGrant(CurrentAccessContext context, UUID warehouseId);

    /** Cross-context form used only by BC-05's fulfillment-owned physical commands. */
    boolean hasActiveGrant(UUID tenantId, UUID workspaceId, UUID membershipId, UUID warehouseId);

    List<WarehouseAccessGrantView> grants(CurrentAccessContext context, UUID warehouseId);

    WarehouseAccessGrantView grant(CurrentAccessContext context, UUID warehouseId, UUID targetMembershipId,
                                   Long expectedVersion, String correlationId);

    WarehouseAccessGrantView revoke(CurrentAccessContext context, UUID warehouseId, UUID targetMembershipId,
                                    long expectedVersion, String correlationId);
}
