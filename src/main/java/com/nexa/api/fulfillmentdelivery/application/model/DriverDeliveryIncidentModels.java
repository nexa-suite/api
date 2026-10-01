package com.nexa.api.fulfillmentdelivery.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Append-only, actor-attributed delivery incident facts. */
public final class DriverDeliveryIncidentModels {
    private DriverDeliveryIncidentModels() { }

    public record IncidentRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID attemptId,
                                  UUID actorMembershipId, long expectedDeliveryVersion,
                                  String idempotencyKey, String requestHash, String reason,
                                  String description, String place, Instant recordedAt) { }

    public record EvidenceRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID attemptId,
                                 UUID incidentId, UUID actorMembershipId, long expectedDeliveryVersion,
                                 String idempotencyKey, String requestHash, List<UUID> evidenceObjectIds,
                                 Instant attachedAt) {
        public EvidenceRequest {
            evidenceObjectIds = List.copyOf(evidenceObjectIds == null ? List.of() : evidenceObjectIds);
        }
    }

    public record IncidentView(UUID id, UUID deliveryId, UUID attemptId, String reason,
                               String description, String place, UUID recordedByMembershipId,
                               Instant recordedAt, List<UUID> evidenceObjectIds,
                               long deliveryVersion, boolean replayed) {
        public IncidentView {
            evidenceObjectIds = List.copyOf(evidenceObjectIds == null ? List.of() : evidenceObjectIds);
        }
    }
}
