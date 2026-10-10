package com.nexa.api.businessdocuments.tenantdatabase;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OrganizationDocumentSourceQuery;

import java.util.Optional;
import java.util.UUID;

/** Reads a central BC-01 document snapshot only for a verified READY Tenant/Workspace pair. */
@FunctionalInterface
public interface TenantBusinessDocumentOrganizationSnapshotQuery {
    Optional<OrganizationDocumentSourceQuery.Snapshot> find(UUID tenantId, UUID workspaceId);
}
