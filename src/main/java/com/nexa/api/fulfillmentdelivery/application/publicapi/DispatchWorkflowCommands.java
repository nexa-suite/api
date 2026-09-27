package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Narrow command boundary for the accepted canonical outbox workflow. */
public interface DispatchWorkflowCommands {
    void create(CurrentAccessContext context, String reservationId, long version, String key);
}
