package com.nexa.api.businessdocuments.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Narrow command boundary for the accepted canonical outbox workflow. */
public interface DocumentGenerationCommands {
    void request(CurrentAccessContext context, String subjectType, java.util.UUID subjectId, String documentType, String format, String key);
}
