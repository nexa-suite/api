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
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class OrganizationAdministrationService implements OrganizationAdministrationUseCase {
	private final OrganizationAdministrationPort port;
	private final SecurityAuditPort audit;

	public OrganizationAdministrationService(OrganizationAdministrationPort port) { this(port, event -> { }); }
	public OrganizationAdministrationService(OrganizationAdministrationPort port, SecurityAuditPort audit) { this.port = Objects.requireNonNull(port); this.audit = Objects.requireNonNull(audit); }

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
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> changeRoles(CurrentAccessContext context, String membershipId,
			Set<MembershipRole> roles, long expectedVersion, String correlationId) {
		manage(context);
		var current = findMembership(context, membershipId);
		Set<MembershipRole> before = parseRoles(current);
		if (roles == null || roles.isEmpty() || roles.contains(MembershipRole.BUYER) || before.contains(MembershipRole.BUYER)) {
			throw new OrganizationAdministrationInvariantViolation("Cross-surface role conversion is not allowed");
		}
		if (before.contains(MembershipRole.TENANT_ADMIN) && !roles.contains(MembershipRole.TENANT_ADMIN)
				&& port.activeTenantAdminCount(current.workspaceId()) <= 1) {
			throw new OrganizationAdministrationInvariantViolation("At least one active tenant admin must remain");
		}
		Set<String> roleNames = roles.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
		if (port.updateRoles(context.tenantId().toString(), current.id(), roleNames, expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("ROLE_CHANGED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(),
				String.join(",", before.stream().map(Enum::name).sorted().toList()), current.status(), String.join(",", roleNames.stream().sorted().toList()), current.status(), correlationId);
		audit(context, "ROLE_ASSIGNMENT_CHANGED", current.id(), correlationId, java.util.Map.of("beforeRoles", before.stream().map(Enum::name).sorted().toList(), "afterRoles", roleNames.stream().sorted().toList()));
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> suspendMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId) {
		manage(context);
		var current = findMembership(context, membershipId);
		if ("DISABLED".equals(current.status())) return new OrganizationAdministrationResult<>(current, current.version());
		if (parseRoles(current).contains(MembershipRole.TENANT_ADMIN) && port.activeTenantAdminCount(current.workspaceId()) <= 1) throw new OrganizationAdministrationInvariantViolation("At least one active tenant admin must remain");
		if (port.updateStatus(context.tenantId().toString(), current.id(), "DISABLED", expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("MEMBERSHIP_SUSPENDED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(), String.join(",", current.roles()), current.status(), String.join(",", current.roles()), "DISABLED", correlationId);
		audit(context, "MEMBERSHIP_SUSPENDED", current.id(), correlationId, java.util.Map.of("status", "DISABLED"));
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> reactivateMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId) {
		manage(context);
		var current = findMembership(context, membershipId);
		if (port.updateStatus(context.tenantId().toString(), current.id(), "ACTIVE", expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("MEMBERSHIP_REACTIVATED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(), String.join(",", current.roles()), current.status(), String.join(",", current.roles()), "ACTIVE", correlationId);
		audit(context, "MEMBERSHIP_REACTIVATED", current.id(), correlationId, java.util.Map.of("status", "ACTIVE"));
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	private WorkspaceSummary findWorkspace(CurrentAccessContext context, String id) {
		try { return port.findWorkspace(context.tenantId().toString(), new WorkspaceId(id).toString()).orElseThrow(() -> new ApiResourceNotFoundException("workspace")); }
		catch (TenantManagementInvariantViolation exception) { throw new ApiResourceNotFoundException("workspace"); }
	}
	private WorkspaceMembershipSummary findMembership(CurrentAccessContext context, String id) {
		return port.findMembership(context.tenantId().toString(), id).filter(value -> value.workspaceId().equals(context.workspaceId().toString())).orElseThrow(() -> new ApiResourceNotFoundException("membership"));
	}
	private static Set<MembershipRole> parseRoles(WorkspaceMembershipSummary value) {
		return value.roles().stream().map(MembershipRole::from).collect(Collectors.toUnmodifiableSet());
	}
	private static void read(CurrentAccessContext context) { context.requirePermission(Permission.TENANT_READ); }
	private static void manage(CurrentAccessContext context) { context.requirePermission(Permission.TENANT_MANAGE); }
	private void audit(CurrentAccessContext context, String type, String targetMembershipId, String correlationId, java.util.Map<String, Object> metadata) {
		audit.append(new SecurityAuditPort.Event(type, context.userId().value(), java.util.UUID.fromString(targetMembershipId),
				context.tenantId().value(), context.workspaceId().value(), context.surface().name(), correlationId == null ? "unknown" : correlationId, "unknown", java.time.Instant.now(), metadata));
	}

	public static final class ConcurrencyConflictException extends RuntimeException { }
}
