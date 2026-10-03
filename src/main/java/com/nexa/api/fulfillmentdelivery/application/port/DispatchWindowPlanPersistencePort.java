package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.DispatchWindowPlanModels.View;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Append-only BC-06 plan for a Fulfillment lacking an authoritative existing window. */
public interface DispatchWindowPlanPersistencePort {
    Optional<View> findReplay(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                              String idempotencyKey, String requestHash);

    View record(RecordCommand command);

    record RecordCommand(UUID tenantId,
                         UUID workspaceId,
                         UUID fulfillmentId,
                         UUID actorMembershipId,
                         long expectedFulfillmentVersion,
                         Instant windowStart,
                         Instant windowEnd,
                         String reason,
                         String idempotencyKey,
                         String requestHash,
                         Instant recordedAt) { }
}
