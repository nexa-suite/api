package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;

import java.util.List;

/** Logistics-owned persistence boundary for Warehouse operational handoffs. */
public interface OperationalHandoffPort {
    List<LogisticsOperationsService.HandoffNoteView> notes(String tenantId, String workspaceId,
                                                            String clientAccountId, String dispatchId);

    LogisticsOperationsService.HandoffNoteView append(String tenantId, String workspaceId,
                                                      String dispatchId, long expectedVersion,
                                                      String actorMembershipId, String idempotencyKey,
                                                      String note, long now);
}
