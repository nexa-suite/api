package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.IncidentView;
import com.nexa.api.fulfillmentdelivery.domain.operationalexception.DriverDeliveryIncidentType;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;

/** Actor-attributed delivery incident and evidence use cases. */
public interface DriverDeliveryIncidentOperations {
    IncidentView record(CurrentAccessContext context, UUID deliveryId, UUID attemptId,
                        long expectedDeliveryVersion, String idempotencyKey, String reason,
                        String description, String place, DriverDeliveryIncidentType type);

    IncidentView attachEvidence(CurrentAccessContext context, UUID deliveryId, UUID attemptId,
                                UUID incidentId, long expectedDeliveryVersion, String idempotencyKey,
                                List<UUID> requestedEvidenceIds);
}
