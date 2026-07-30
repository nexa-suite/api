package com.nexa.api.tenantmanagement.application.service;

import com.nexa.api.tenantmanagement.application.model.*;
import com.nexa.api.tenantmanagement.application.port.in.OrganizationAdministrationUseCase;
import com.nexa.api.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.administration.OrganizationAdministrationInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.shared.presentation.error.ApiResourceNotFoundException;
import java.util.List;
import java.util.Objects;

public final class OrganizationAdministrationService implements OrganizationAdministrationUseCase {
	private final OrganizationAdministrationPort port;

	public OrganizationAdministrationService(OrganizationAdministrationPort port) { this.port = Objects.requireNonNull(port); }

	@Override
	public OrganizationSummary organization(CurrentAccessContext context) {
		read(context);
		return port.findOrganization(context.tenantId().toString(), context.workspaceId().toString()).orElseThrow(() -> new ApiResourceNotFoundException("organization"));
	}

	@Override
	public List<WorkspaceSummary> workspaces(CurrentAccessContext context) {
		read(context); return port.findWorkspaces(context.tenantId().toString());
	}

	@Override
	public WorkspaceDetails workspace(CurrentAccessContext context, String workspaceId) {
		read(context);
		var workspace = findWorkspace(context, workspaceId);
		return new WorkspaceDetails(workspace, port.findMemberships(context.tenantId().toString(), workspace.id()));
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceSummary> updateWorkspace(CurrentAccessContext context, String workspaceId,
			String name, WorkspaceStatus status, long expectedVersion, String correlationId) {
		manage(context);
		var current = findWorkspace(context, workspaceId);
		if (name == null || name.isBlank()) name = current.name();
		if (status == null) status = WorkspaceStatus.from(current.status());
		if (port.updateWorkspace(context.tenantId().toString(), current.id(), name.trim(), status.name(), expectedVersion) == 0) throw new ConcurrencyConflictException();
		return new OrganizationAdministrationResult<>(findWorkspace(context, current.id()), expectedVersion + 1);
	}

	@Override
	public List<WorkspaceMembershipSummary> memberships(CurrentAccessContext context) {
		read(context); return port.findMemberships(context.tenantId().toString(), context.workspaceId().toString());
	}

	@Override
	public WorkspaceMembershipSummary membership(CurrentAccessContext context, String membershipId) {
		read(context); return findMembership(context, membershipId);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> changeRole(CurrentAccessContext context, String membershipId,
			MembershipRole role, long expectedVersion, String correlationId) {
		manage(context);
		var current = findMembership(context, membershipId);
		MembershipRole before = MembershipRole.from(current.role());
		if (role == null || role == MembershipRole.BUYER || before == MembershipRole.BUYER) throw new OrganizationAdministrationInvariantViolation("Cross-surface role conversion is not allowed");
		if (before == MembershipRole.COMPANY_OWNER && role != before && port.activeOwnerCount(current.workspaceId()) <= 1) throw new OrganizationAdministrationInvariantViolation("At least one active company owner must remain");
		if (port.updateRole(context.tenantId().toString(), current.id(), role.name(), expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("ROLE_CHANGED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(), before.name(), current.status(), role.name(), current.status(), correlationId);
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> suspendMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId) {
		manage(context);
		var current = findMembership(context, membershipId);
		if ("DISABLED".equals(current.status())) return new OrganizationAdministrationResult<>(current, current.version());
		if (MembershipRole.COMPANY_OWNER.name().equals(current.role()) && port.activeOwnerCount(current.workspaceId()) <= 1) throw new OrganizationAdministrationInvariantViolation("At least one active company owner must remain");
		if (port.updateStatus(context.tenantId().toString(), current.id(), "DISABLED", expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("MEMBERSHIP_SUSPENDED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(), current.role(), current.status(), current.role(), "DISABLED", correlationId);
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> reactivateMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId) {
		manage(context);
		var current = findMembership(context, membershipId);
		if (port.updateStatus(context.tenantId().toString(), current.id(), "ACTIVE", expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("MEMBERSHIP_REACTIVATED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(), current.role(), current.status(), current.role(), "ACTIVE", correlationId);
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	private WorkspaceSummary findWorkspace(CurrentAccessContext context, String id) {
		try { return port.findWorkspace(context.tenantId().toString(), new WorkspaceId(id).toString()).orElseThrow(() -> new ApiResourceNotFoundException("workspace")); }
		catch (TenantManagementInvariantViolation exception) { throw new ApiResourceNotFoundException("workspace"); }
	}
	private WorkspaceMembershipSummary findMembership(CurrentAccessContext context, String id) {
		return port.findMembership(context.tenantId().toString(), id).filter(value -> value.workspaceId().equals(context.workspaceId().toString())).orElseThrow(() -> new ApiResourceNotFoundException("membership"));
	}
	private static void read(CurrentAccessContext context) { context.requirePermission(Permission.TENANT_READ); }
	private static void manage(CurrentAccessContext context) { context.requirePermission(Permission.TENANT_MANAGE); }

	public static final class ConcurrencyConflictException extends RuntimeException { }
}
