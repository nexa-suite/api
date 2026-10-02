package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Assignee;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Command;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.ExceptionView;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Snapshot;

import java.util.List;
import java.util.UUID;

/** BC-06 persistence boundary for administrative coordination, separate from operational outcomes. */
public interface BomOperationalExceptionPersistencePort {
    Snapshot list(UUID tenantId, UUID workspaceId);
    ExceptionView find(UUID tenantId, UUID workspaceId, UUID exceptionId);
    List<Assignee> assignees(UUID tenantId, UUID workspaceId, UUID exceptionId,
                            List<com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory.ExceptionAssignee> candidates);
    boolean isCurrentDriverAssignment(UUID tenantId, UUID workspaceId, UUID exceptionId, UUID membershipId);
    MutationResult mutate(Command command);
}
