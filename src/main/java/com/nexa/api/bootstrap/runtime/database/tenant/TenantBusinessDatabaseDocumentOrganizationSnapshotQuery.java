package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentOrganizationSnapshotQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OrganizationDocumentSourceQuery;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads central BC-01 organization facts for one independently verified READY scope. */
public final class TenantBusinessDatabaseDocumentOrganizationSnapshotQuery
		implements TenantBusinessDocumentOrganizationSnapshotQuery {
	private final TenantBusinessDocumentWorkerScopeQuery scopes;
	private final OrganizationDocumentSourceQuery organizations;

	public TenantBusinessDatabaseDocumentOrganizationSnapshotQuery(TenantBusinessDocumentWorkerScopeQuery scopes,
			OrganizationDocumentSourceQuery organizations) {
		this.scopes = Objects.requireNonNull(scopes, "READY Tenant scope query is required");
		this.organizations = Objects.requireNonNull(organizations, "Central organization source is required");
	}

	@Override
	public Optional<OrganizationDocumentSourceQuery.Snapshot> find(UUID tenantId, UUID workspaceId) {
		Objects.requireNonNull(tenantId, "Tenant scope is required");
		Objects.requireNonNull(workspaceId, "Workspace scope is required");
		TenantBusinessDatabaseRouter.ensureRouteCanStart();
		if (!scopes.isReadyWorkspace(tenantId, workspaceId)) return Optional.empty();

		RlsRequestScope.Scope previousScope = RlsRequestScope.current();
		boolean previousCrossScope = RlsRequestScope.crossScopeWorkspaceScanEnabled();
		try {
			RlsRequestScope.clear();
			RlsRequestScope.set(tenantId, workspaceId);
			Optional<OrganizationDocumentSourceQuery.Snapshot> snapshot = organizations.find(tenantId, workspaceId);
			return scopes.isReadyWorkspace(tenantId, workspaceId) ? snapshot : Optional.empty();
		} finally {
			RlsRequestScope.clear();
			if (previousScope != null) RlsRequestScope.set(previousScope.tenantId(), previousScope.workspaceId());
			if (previousCrossScope) RlsRequestScope.enableCrossScopeWorkspaceScan();
		}
	}
}
