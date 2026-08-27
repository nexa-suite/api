package com.nexa.api.salescommitment.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Sales-owned lifecycle facts updated by the Fulfillment application boundary. */
public interface SalesOrderFulfillmentCommands {
    void markInFulfillment(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                           UUID actorMembershipId, Instant now);

    void markPartiallyDelivered(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                                UUID actorMembershipId, Instant now, String reason);

    default void markCompleted(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                               UUID actorMembershipId, Instant now, String reason) {
        markCompleted(tenantId, workspaceId, salesOrderId, actorMembershipId, now, reason, BigDecimal.ZERO);
    }

    void markCompleted(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                       UUID actorMembershipId, Instant now, String reason,
                       BigDecimal unresolvedQuantity);
}
