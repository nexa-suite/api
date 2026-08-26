package com.nexa.api.businessdocuments.application.publicapi;

import java.util.UUID;

/** BC-09 read boundary for attaching an already scanned evidence object. */
public interface BusinessEvidenceQuery {
    boolean isAvailable(UUID tenantId, UUID workspaceId, UUID evidenceObjectId);
}
