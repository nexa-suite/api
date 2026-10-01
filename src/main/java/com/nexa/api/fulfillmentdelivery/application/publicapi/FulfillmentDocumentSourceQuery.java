package com.nexa.api.fulfillmentdelivery.application.publicapi;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Immutable Fulfillment & Delivery facts required to render business documents. */
public interface FulfillmentDocumentSourceQuery {
    Optional<Dispatch> findDispatch(UUID tenantId, UUID workspaceId, UUID dispatchId);

    Optional<ProofOfDelivery> findPod(UUID tenantId, UUID workspaceId, UUID proofOfDeliveryId);

    Optional<Incident> findIncident(UUID tenantId, UUID workspaceId, UUID incidentId);

    /** Minimal customer-scoped projection used only to bind BC-09 incident evidence to its subject. */
    Optional<IncidentSubject> findIncidentSubject(UUID tenantId, UUID workspaceId, UUID incidentId);

    record Dispatch(UUID id, UUID customerAccountId, String dispatchNumber, String status,
                    String destinationSnapshot, Instant deliveryWindowStart, Instant eta,
                    String responsibleDisplayNameSnapshot, String vehicleReference,
                    String routeName, String temperatureStatus, UUID salesOrderId,
                    String temperatureSummary) { }

    record ProofOfDelivery(UUID id, UUID customerAccountId, String receiverName,
                           Instant completedAt, String notes, String status,
                           boolean photoEvidenceDeclared, boolean signatureEvidenceDeclared,
                           UUID dispatchId) { }

    record Incident(UUID id, UUID customerAccountId, String incidentType, String severity,
                    String description, Instant occurredAt, String resolution,
                    UUID dispatchId) { }

    record IncidentSubject(UUID id, UUID customerAccountId, String status) { }
}
