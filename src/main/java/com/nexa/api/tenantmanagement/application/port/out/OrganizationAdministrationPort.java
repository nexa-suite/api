package com.nexa.api.tenantmanagement.application.port.out;

import com.nexa.api.tenantmanagement.application.model.OrganizationSummary;
import com.nexa.api.tenantmanagement.application.model.WorkspaceMembershipSummary;
import com.nexa.api.tenantmanagement.application.model.WorkspaceSummary;

import java.util.List;
import java.util.Optional;

public interface OrganizationAdministrationPort {
	Optional<OrganizationSummary> findOrganization(String tenantId, String workspaceId);
	List<WorkspaceSummary> findWorkspaces(String tenantId);
	Optional<WorkspaceSummary> findWorkspace(String tenantId, String workspaceId);
	List<WorkspaceMembershipSummary> findMemberships(String tenantId, String workspaceId);
	Optional<WorkspaceMembershipSummary> findMembership(String tenantId, String membershipId);
	int updateWorkspace(String tenantId, String workspaceId, String name, String status, long expectedVersion);
	int activeOwnerCount(String workspaceId);
	int updateRole(String tenantId, String membershipId, String role, long expectedVersion);
	int updateStatus(String tenantId, String membershipId, String status, long expectedVersion);
	void appendMembershipEvent(String type, String tenantId, String workspaceId, String targetMembershipId,
			String actorMembershipId, String beforeRole, String beforeStatus, String afterRole, String afterStatus,
			String correlationId);
}
