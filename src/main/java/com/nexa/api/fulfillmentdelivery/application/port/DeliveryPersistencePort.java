package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.DeliveryOutcomeResult;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.DeliveryView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.PodView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.TemperatureView;
import com.nexa.api.fulfillmentdelivery.domain.model.delivery.DeliveryAttemptOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** BC-06 delivery outcome port. */
public interface DeliveryPersistencePort {
    DeliveryView find(UUID tenantId, UUID workspaceId, UUID deliveryId);

    DeliveryView transition(TransitionRequest request);

    DeliveryOutcomeResult recordAttempt(AttemptRequest request);

    PodView capturePod(PodRequest request);

    PodView sealPod(PodSealRequest request);

    TemperatureView recordTemperature(TemperatureRequest request);

    record TransitionRequest(UUID tenantId, UUID workspaceId, UUID deliveryId,
                             long expectedVersion, UUID actorMembershipId, String operation,
                             String targetStatus, String idempotencyKey, String requestHash,
                             String reason, Instant now) { }

    record AttemptRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID clientAccountId,
                          long expectedVersion, UUID actorMembershipId, String idempotencyKey,
                          String requestHash, DeliveryAttemptOutcome outcome, String failureReason,
                          String notes, Instant attemptedAt, List<AttemptLine> lines) {
        public AttemptRequest {
            if (tenantId == null || workspaceId == null || deliveryId == null || clientAccountId == null
                    || actorMembershipId == null || idempotencyKey == null || idempotencyKey.isBlank()
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}") || outcome == null) {
                throw new IllegalArgumentException("Delivery attempt request is incomplete");
            }
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    record AttemptLine(UUID fulfillmentLineId, UUID skuId, BigDecimal attemptedQuantity,
                       BigDecimal deliveredQuantity, BigDecimal rejectedQuantity,
                       BigDecimal cancelledQuantity, BigDecimal unitPriceAmount,
                       String currency, String unit) { }

    record PodRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, long expectedVersion,
                      UUID actorMembershipId, String idempotencyKey, String requestHash,
                      String receiverName, Instant capturedAt, String notes,
                      UUID photoEvidenceObjectId, UUID signatureEvidenceObjectId) { }

    record PodSealRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, long expectedVersion,
                          UUID actorMembershipId, String idempotencyKey, String requestHash, Instant sealedAt) { }

    record TemperatureRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId,
                              UUID actorMembershipId, String idempotencyKey, String requestHash,
                              BigDecimal temperatureCelsius, String unit, String source,
                              String evidenceMetadata, Instant recordedAt, long expectedVersion) { }
}
