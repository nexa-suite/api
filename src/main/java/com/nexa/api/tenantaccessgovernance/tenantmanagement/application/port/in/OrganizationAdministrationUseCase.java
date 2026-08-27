package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.OrganizationAdministrationResult;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.OrganizationSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.WorkspaceDetails;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.WorkspaceMembershipSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.WorkspaceSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;

import java.util.List;
import java.util.Set;

public interface OrganizationAdministrationUseCase {
	OrganizationSummary organization(CurrentAccessContext context);
	List<WorkspaceSummary> workspaces(CurrentAccessContext context);
	WorkspaceDetails workspace(CurrentAccessContext context, String workspaceId);
	OrganizationAdministrationResult<WorkspaceSummary> createWorkspace(CurrentAccessContext context, String name, String slug, String idempotencyKey, String correlationId);
	OrganizationAdministrationResult<WorkspaceSummary> updateWorkspace(CurrentAccessContext context, String workspaceId,
			String name, String slug, WorkspaceStatus status, long expectedVersion, String correlationId);
	OrganizationAdministrationResult<WorkspaceSummary> suspendWorkspace(CurrentAccessContext context, String workspaceId, long expectedVersion, String correlationId);
	OrganizationAdministrationResult<WorkspaceSummary> reactivateWorkspace(CurrentAccessContext context, String workspaceId, long expectedVersion, String correlationId);
	List<WorkspaceMembershipSummary> memberships(CurrentAccessContext context);
	WorkspaceMembershipSummary membership(CurrentAccessContext context, String membershipId);
	OrganizationAdministrationResult<WorkspaceMembershipSummary> changeRoles(CurrentAccessContext context, String membershipId,
			Set<MembershipRole> roles, long expectedVersion, String correlationId);
	OrganizationAdministrationResult<WorkspaceMembershipSummary> changeRoleDefinitions(CurrentAccessContext context, String membershipId,
			Set<String> roleDefinitionIds, long expectedVersion, String correlationId);
	OrganizationAdministrationResult<WorkspaceMembershipSummary> suspendMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId);
	OrganizationAdministrationResult<WorkspaceMembershipSummary> reactivateMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId);
}
