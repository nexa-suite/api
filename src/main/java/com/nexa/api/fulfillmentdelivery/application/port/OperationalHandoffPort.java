package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Logistics-owned persistence boundary for Warehouse operational handoffs. */
public interface OperationalHandoffPort {
    List<LogisticsOperationsModels.HandoffNoteView> notes(String tenantId, String workspaceId,
                                                            String clientAccountId, String dispatchId);

    default List<LogisticsOperationsModels.HandoffNoteView> notes(String tenantId, String workspaceId,
                                                                   String clientAccountId, String dispatchId,
                                                                   Set<UUID> grantedWarehouseIds) {
        return List.of();
    }

    LogisticsOperationsModels.HandoffNoteView append(String tenantId, String workspaceId,
                                                      String dispatchId, long expectedVersion,
                                                      String actorMembershipId, String idempotencyKey,
                                                      String note, Set<UUID> grantedWarehouseIds, long now);
}
