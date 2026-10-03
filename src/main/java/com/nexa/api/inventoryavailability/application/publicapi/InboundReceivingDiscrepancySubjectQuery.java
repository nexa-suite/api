package com.nexa.api.inventoryavailability.application.publicapi;

import java.util.Optional;
import java.util.UUID;

/** BC-05 read contract used by BC-09 to validate an evidence subject in scope. */
public interface InboundReceivingDiscrepancySubjectQuery {
    Optional<Subject> find(UUID tenantId, UUID workspaceId, UUID membershipId, UUID caseId);

    record Subject(UUID id, String lifecycleState) { }
}
