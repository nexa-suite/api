package com.nexa.api.payments.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Narrow command boundary for the accepted canonical outbox workflow. */
public interface PaymentWorkflowCommands {
    void createReceivable(CurrentAccessContext context, String subjectType, java.util.UUID subjectId, String key);
}
