package com.nexa.api.tenantmanagement.application.port.in;

import com.nexa.api.tenantmanagement.application.model.OrganizationAdministrationResult;
import com.nexa.api.tenantmanagement.application.model.OrganizationSummary;
import com.nexa.api.tenantmanagement.application.model.WorkspaceDetails;
import com.nexa.api.tenantmanagement.application.model.WorkspaceMembershipSummary;
import com.nexa.api.tenantmanagement.application.model.WorkspaceSummary;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;

import java.util.List;

public interface OrganizationAdministrationUseCase {
	OrganizationSummary organization(CurrentAccessContext context);
	List<WorkspaceSummary> workspaces(CurrentAccessContext context);
	WorkspaceDetails workspace(CurrentAccessContext context, String workspaceId);
	OrganizationAdministrationResult<WorkspaceSummary> updateWorkspace(CurrentAccessContext context, String workspaceId,
			String name, WorkspaceStatus status, long expectedVersion, String correlationId);
	List<WorkspaceMembershipSummary> memberships(CurrentAccessContext context);
	WorkspaceMembershipSummary membership(CurrentAccessContext context, String membershipId);
	OrganizationAdministrationResult<WorkspaceMembershipSummary> changeRole(CurrentAccessContext context, String membershipId,
			MembershipRole role, long expectedVersion, String correlationId);
	OrganizationAdministrationResult<WorkspaceMembershipSummary> suspendMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId);
	OrganizationAdministrationResult<WorkspaceMembershipSummary> reactivateMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId);
}
