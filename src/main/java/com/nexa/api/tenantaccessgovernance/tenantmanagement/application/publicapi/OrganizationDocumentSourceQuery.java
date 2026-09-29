package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.Optional;
import java.util.UUID;

/** Tenant-owned organization and regional facts required by business documents. */
public interface OrganizationDocumentSourceQuery {
    Optional<Snapshot> find(UUID tenantId, UUID workspaceId);

    record Snapshot(String tenantName, String legalName, String businessIdentifier,
                    String regionalCurrency) { }
}
