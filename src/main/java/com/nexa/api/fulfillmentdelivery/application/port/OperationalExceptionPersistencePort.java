package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ClaimRequest;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.DriverIncidentSourceRequest;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ExceptionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ReviewRequest;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.TypedIncidentSourceRequest;

import java.util.UUID;

/** BC-06 source projection and assigned-Driver claim/review persistence boundary. */
public interface OperationalExceptionPersistencePort {
    ExceptionSetView findForDriver(UUID tenantId, UUID workspaceId, UUID actorMembershipId, UUID deliveryId);

    MutationResult claim(ClaimRequest request);

    MutationResult review(ReviewRequest request);

    /** Persists the immutable typed Dispatch incident and any Owner-classified case atomically. */
    void recordTypedIncident(TypedIncidentSourceRequest request);

    /** Materializes a typed immutable Driver source while its Delivery row is already locked. */
    UUID materializeDriverIncident(DriverIncidentSourceRequest request);
}
