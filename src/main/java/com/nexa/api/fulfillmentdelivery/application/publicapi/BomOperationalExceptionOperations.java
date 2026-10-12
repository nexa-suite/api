package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Assignee;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Snapshot;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;

/** Cross-functional operational-exception coordination use cases. */
public interface BomOperationalExceptionOperations {
    Snapshot list(CurrentAccessContext context);

    List<Assignee> assignees(CurrentAccessContext context, UUID exceptionId);

    MutationResult mutate(CurrentAccessContext context, UUID exceptionId, String operation,
                          UUID targetMembershipId, long expectedVersion, String idempotencyKey,
                          String reason, String note);
}
