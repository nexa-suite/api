package com.nexa.api.fulfillmentdelivery.application.publicapi;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Immutable Fulfillment & Delivery facts required to render business documents. */
public interface FulfillmentDocumentSourceQuery {
    Optional<Dispatch> findDispatch(UUID tenantId, UUID workspaceId, UUID dispatchId);

    Optional<ProofOfDelivery> findPod(UUID tenantId, UUID workspaceId, UUID proofOfDeliveryId);

    /** Minimal POD fact lookup for binding uploaded evidence before the POD is sealed. */
    Optional<ProofOfDelivery> findPodEvidenceSubject(UUID tenantId, UUID workspaceId, UUID proofOfDeliveryId,
                                                      UUID actorMembershipId, UUID actorUserId);

    Optional<ProofOfDelivery> findSealedPodForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId);

    Optional<Incident> findIncident(UUID tenantId, UUID workspaceId, UUID incidentId);

    /** Minimal customer-scoped projection used only to bind BC-09 incident evidence to its subject. */
    Optional<IncidentSubject> findIncidentSubject(UUID tenantId, UUID workspaceId, UUID incidentId);

    /** Buyer receipt fact joined to its actual Delivery and attempt before it can own BC-09 evidence. */
    Optional<BuyerReceiptSubject> findBuyerReceipt(UUID tenantId, UUID workspaceId, UUID receiptId);

    record Dispatch(UUID id, UUID customerAccountId, String dispatchNumber, String status,
                    String destinationSnapshot, Instant deliveryWindowStart, Instant eta,
                    String responsibleDisplayNameSnapshot, String vehicleReference,
                    String routeName, String temperatureStatus, UUID salesOrderId,
                    String temperatureSummary) { }

    record ProofOfDelivery(UUID id, UUID customerAccountId, String receiverName,
                           Instant completedAt, String notes, String status,
                           boolean photoEvidenceDeclared, boolean signatureEvidenceDeclared,
                           UUID dispatchId, UUID deliveryId, UUID salesOrderId,
                           String destinationSnapshot, String temperatureStatus,
                           String temperatureSummary) { }

    record Incident(UUID id, UUID customerAccountId, String incidentType, String severity,
                    String description, Instant occurredAt, String resolution,
                    UUID dispatchId) { }

    record IncidentSubject(UUID id, UUID customerAccountId, String status, UUID salesOrderId) { }

    record BuyerReceiptSubject(UUID id, UUID deliveryId, UUID deliveryAttemptId, UUID customerAccountId,
                               UUID buyerMembershipId, String decision, Instant occurredAt) { }
}
