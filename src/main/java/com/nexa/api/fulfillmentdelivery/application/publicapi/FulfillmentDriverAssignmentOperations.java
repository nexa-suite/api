package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentDriverAssignmentModels.AssignmentView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FulfillmentDriverAssignmentOperations {
    AssignmentView assign(CurrentAccessContext context, UUID fulfillmentId,
                          long expectedFulfillmentVersion, UUID expectedAllocationId,
                          long expectedAllocationVersion, UUID responsibleMembershipId,
                          String idempotencyKey);
    AssignmentView changePlan(CurrentAccessContext context, UUID fulfillmentId,
                              long expectedFulfillmentVersion, UUID expectedAssignmentId,
                              long expectedAssignmentVersion, UUID expectedAllocationId,
                              long expectedAllocationVersion, UUID requestedMembershipId,
                              Instant requestedDispatchAt, String idempotencyKey);
    Optional<AssignmentView> current(CurrentAccessContext context, UUID fulfillmentId);
    List<AssignmentView> history(CurrentAccessContext context, UUID fulfillmentId);
}
