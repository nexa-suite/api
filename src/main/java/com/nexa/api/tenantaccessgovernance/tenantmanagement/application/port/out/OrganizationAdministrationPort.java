package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.OrganizationSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.WorkspaceMembershipSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.WorkspaceSummary;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;

public interface OrganizationAdministrationPort {
	Optional<OrganizationSummary> findOrganization(String tenantId, String workspaceId);
	List<WorkspaceSummary> findWorkspaces(String tenantId);
	default boolean tenantHasWorkspace(String tenantId) { return !findWorkspaces(tenantId).isEmpty(); }
	Optional<WorkspaceSummary> findWorkspace(String tenantId, String workspaceId);
	int createWorkspace(String tenantId, UUID workspaceId, String name, String slug, Instant createdAt);
	Optional<UUID> findWorkspaceIdempotent(String tenantId, String idempotencyKey, String requestHash);
	boolean workspaceIdempotencyKeyHasDifferentPayload(String tenantId, String idempotencyKey, String requestHash);
	int saveWorkspaceIdempotency(String tenantId, String idempotencyKey, String requestHash, UUID workspaceId);
	void createWorkspaceMembership(String tenantId, String workspaceId, UUID userId, Set<String> roles, Instant createdAt);
	List<WorkspaceMembershipSummary> findMemberships(String tenantId, String workspaceId);
	default List<WorkspaceMembershipSummary> findMemberships(String tenantId) { return List.of(); }
	Optional<WorkspaceMembershipSummary> findMembership(String tenantId, String membershipId);
	int updateWorkspace(String tenantId, String workspaceId, String name, String slug, String status, long expectedVersion);
	int updateWorkspaceStatus(String tenantId, String workspaceId, String status, long expectedVersion);
	void lockTenant(String tenantId);
	int activeAdministrativeWorkspaceCount(String tenantId);
	int activeOwnerCount(String workspaceId);
	default int activeCompanyOwnerCount(String tenantId) { return 0; }
	int activeTenantAdminCount(String workspaceId);
	int updateRoles(String tenantId, String membershipId, java.util.Set<String> roles, long expectedVersion);
	/** Primary implements the dynamic assignment table and increments membership authorizationVersion. */
	default int updateRoleDefinitionAssignments(String tenantId, String membershipId, java.util.Set<String> roleDefinitionIds, long expectedVersion) {
		throw new UnsupportedOperationException("Dynamic role assignment persistence is not integrated");
	}
	int updateStatus(String tenantId, String membershipId, String status, long expectedVersion);
	void appendMembershipEvent(String type, String tenantId, String workspaceId, String targetMembershipId,
			String actorMembershipId, String beforeRole, String beforeStatus, String afterRole, String afterStatus,
			String correlationId);
}
