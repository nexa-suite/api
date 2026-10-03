package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.EvidenceRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.IncidentRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.IncidentView;

/** BC-06 persistence boundary for append-only current-driver incident evidence. */
public interface DriverDeliveryIncidentPersistencePort {
    IncidentView recordIncident(IncidentRequest request);

    /** Acquires the command lock and returns an exact replay when one already exists. */
    IncidentView findEvidenceReplay(EvidenceRequest request);

    IncidentView appendEvidence(EvidenceRequest request);
}
