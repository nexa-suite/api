package com.nexa.api.salescommitment.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Narrow command boundary for the accepted canonical outbox workflow. */
public interface SalesWorkflowCommands {
    void convert(CurrentAccessContext context, String requestId, long version, String key, String note);
}
