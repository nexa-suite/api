package com.nexa.api.businessdocuments.application.publicapi;

import java.util.UUID;

/** BC-09 read boundary for attaching an already scanned evidence object. */
public interface BusinessEvidenceQuery {
    boolean isAvailable(UUID tenantId, UUID workspaceId, UUID evidenceObjectId);

    long countAvailableForSubject(UUID tenantId, UUID workspaceId, String subjectType, UUID subjectId);

    /** Availability plus immutable subject binding; ownership remains in BC-09. */
    boolean isAvailableForSubject(UUID tenantId, UUID workspaceId, UUID evidenceObjectId,
                                  UUID clientAccountId, String subjectType, UUID subjectId);

    /** Availability plus an exact BC-09 subject binding when a client account is not part of the subject contract. */
    boolean isAvailableForSubject(UUID tenantId, UUID workspaceId, UUID evidenceObjectId,
                                  String subjectType, UUID subjectId);
}
