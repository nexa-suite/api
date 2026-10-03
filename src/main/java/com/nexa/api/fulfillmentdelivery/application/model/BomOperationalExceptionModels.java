package com.nexa.api.fulfillmentdelivery.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Cross-functional coordination projections; this lifecycle never changes Delivery or stock outcomes. */
public final class BomOperationalExceptionModels {
    private BomOperationalExceptionModels() { }

    public record ExceptionView(UUID id, UUID deliveryId, long deliveryVersion, String sourceKind,
            UUID sourceIncidentId, String affectedObjectType, UUID affectedObjectId, String type,
            String severity, String status, String reason, String description, String place,
            String resolution, String outcome, UUID reportedByMembershipId, Instant occurredAt,
            Instant reportedAt, UUID responsibleMembershipId, Instant claimedAt,
            UUID underReviewByMembershipId, Instant underReviewAt, UUID coordinationOwnerMembershipId,
            Instant coordinationClaimedAt, List<UUID> evidenceObjectIds) {
        public ExceptionView {
            evidenceObjectIds = List.copyOf(evidenceObjectIds == null ? List.of() : evidenceObjectIds);
        }
    }

    public record Snapshot(Instant asOf, List<ExceptionView> exceptions) {
        public Snapshot {
            exceptions = List.copyOf(exceptions == null ? List.of() : exceptions);
        }
    }

    public record Assignee(UUID membershipId, String displayName, boolean coordinator, boolean driverReporter) { }

    public record MutationResult(UUID deliveryId, long deliveryVersion, ExceptionView exception, boolean replayed) { }

    public record Command(UUID tenantId, UUID workspaceId, UUID actorMembershipId, UUID exceptionId,
            UUID targetMembershipId, long expectedDeliveryVersion, String operation, String reason,
            String note, String idempotencyKey, String requestHash, Instant occurredAt) { }
}
