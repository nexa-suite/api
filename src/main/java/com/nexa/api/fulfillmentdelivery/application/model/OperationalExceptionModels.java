package com.nexa.api.fulfillmentdelivery.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transport-neutral, actor-attributed operational-exception read and command models. */
public final class OperationalExceptionModels {
    private OperationalExceptionModels() { }

    public record ExceptionView(UUID id, String sourceKind, UUID sourceIncidentId,
                                String affectedObjectType, UUID affectedObjectId, String type,
                                String severity, String status, String reason, String description,
                                String place, String resolution, String outcome,
                                UUID reportedByMembershipId, Instant occurredAt, Instant reportedAt,
                                UUID responsibleMembershipId, Instant claimedAt,
                                UUID underReviewByMembershipId, Instant underReviewAt,
                                List<UUID> evidenceObjectIds) {
        public ExceptionView {
            evidenceObjectIds = List.copyOf(evidenceObjectIds == null ? List.of() : evidenceObjectIds);
        }
    }

    public record ExceptionSetView(UUID deliveryId, long deliveryVersion, List<ExceptionView> exceptions) {
        public ExceptionSetView {
            exceptions = List.copyOf(exceptions == null ? List.of() : exceptions);
        }
    }

    public record MutationResult(UUID deliveryId, long deliveryVersion, ExceptionView exception,
                                 boolean replayed) { }

    public record ClaimRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID exceptionId,
                               UUID actorMembershipId, long expectedDeliveryVersion, String idempotencyKey,
                               String requestHash, Instant claimedAt) { }

    public record ReviewRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID exceptionId,
                                UUID actorMembershipId, long expectedDeliveryVersion, String idempotencyKey,
                                String requestHash, Instant reviewedAt) { }

    public record WarningCompletionRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID exceptionId,
            UUID actorMembershipId, long expectedDeliveryVersion, String idempotencyKey,
            String requestHash, Instant occurredAt, String resolution, boolean close) { }

    public record TypedIncidentSourceRequest(UUID tenantId, UUID workspaceId, UUID dispatchOrderId,
                                             UUID incidentId, String type, String sourceSeverity,
                                             boolean buyerVisible, String description, Instant occurredAt,
                                             String resolution, UUID reportedByMembershipId, Instant reportedAt,
                                             String idempotencyKey, String requestHash) { }

    public record DriverIncidentSourceRequest(UUID tenantId, UUID workspaceId, UUID deliveryId,
                                              UUID incidentId, String type, String severity, String reason,
                                              String description, String place, UUID reportedByMembershipId,
                                              Instant reportedAt, long deliveryVersion,
                                              String idempotencyKey, String requestHash) { }
}
