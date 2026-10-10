package com.nexa.api.businessdocuments.tenantdatabase;

import java.util.UUID;

/** Technical BC-09 work that runs only after Tenant and Workspace scope was centrally verified. */
public interface TenantBusinessDocumentWorkerPort {
    void processPendingEvidenceScans(UUID tenantId, UUID workspaceId);

    void processPendingGenerationRequests(UUID tenantId, UUID workspaceId);
}
