package com.nexa.api.tenantmanagement.application.service;

import com.nexa.api.tenantmanagement.application.model.*;
import com.nexa.api.tenantmanagement.application.port.in.OrganizationAdministrationUseCase;
import com.nexa.api.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.administration.OrganizationAdministrationInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.access.AssignableRoleEnvelope;
import com.nexa.api.tenantmanagement.domain.model.access.AssignableRolePolicy;
import com.nexa.api.tenantmanagement.domain.model.access.RoleCatalog;
import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinitionType;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantmanagement.domain.model.workspace.WorkspaceSlug;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.shared.application.port.out.NoopChangeEventPersistence;
import com.nexa.api.tenantmanagement.application.port.out.RoleDefinitionPersistencePort;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationVersionPort;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class OrganizationAdministrationService implements OrganizationAdministrationUseCase {
	private final OrganizationAdministrationPort port;
	private final SecurityAuditPort audit;
	private final ChangeEventPersistencePort changes;
	private final RoleDefinitionPersistencePort roleDefinitions;
	private final AuthorizationVersionPort authorizationVersions;

	public OrganizationAdministrationService(OrganizationAdministrationPort port) { this(port, event -> { }, new NoopChangeEventPersistence(), null, (tenant, workspace) -> { }); }
	public OrganizationAdministrationService(OrganizationAdministrationPort port, SecurityAuditPort audit) { this(port, audit, new NoopChangeEventPersistence(), null, (tenant, workspace) -> { }); }
	public OrganizationAdministrationService(OrganizationAdministrationPort port, SecurityAuditPort audit, ChangeEventPersistencePort changes) {
		this(port, audit, changes, null, (tenant, workspace) -> { });
	}
	public OrganizationAdministrationService(OrganizationAdministrationPort port, SecurityAuditPort audit, ChangeEventPersistencePort changes,
			RoleDefinitionPersistencePort roleDefinitions) {
		this(port, audit, changes, roleDefinitions, (tenant, workspace) -> { });
	}
	public OrganizationAdministrationService(OrganizationAdministrationPort port, SecurityAuditPort audit, ChangeEventPersistencePort changes,
			RoleDefinitionPersistencePort roleDefinitions, AuthorizationVersionPort authorizationVersions) {
		this.port = Objects.requireNonNull(port);
		this.audit = Objects.requireNonNull(audit);
		this.changes = Objects.requireNonNull(changes);
		this.roleDefinitions = roleDefinitions;
		this.authorizationVersions = Objects.requireNonNull(authorizationVersions);
	}

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
	public OrganizationAdministrationResult<WorkspaceSummary> createWorkspace(CurrentAccessContext context, String name, String slug, String idempotencyKey, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IdempotencyKeyRequiredException();
		String safeName = name == null ? "" : name.strip();
		String safeSlug = new WorkspaceSlug(slug).value();
		if (safeName.isBlank()) throw new OrganizationAdministrationInvariantViolation("Workspace name is required");
		String requestHash = sha256(safeName + "|" + safeSlug);
		if (port.workspaceIdempotencyKeyHasDifferentPayload(context.tenantId().toString(), idempotencyKey, requestHash)) throw new IdempotencyPayloadConflictException();
		var previous = port.findWorkspaceIdempotent(context.tenantId().toString(), idempotencyKey, requestHash);
		if (previous.isPresent()) {
			WorkspaceSummary existing = port.findWorkspace(context.tenantId().toString(), previous.get().toString()).orElseThrow(() -> new ApiResourceNotFoundException("workspace"));
			return new OrganizationAdministrationResult<>(existing, existing.version());
		}
		port.lockTenant(context.tenantId().toString());
		if (port.tenantHasWorkspace(context.tenantId().toString())) {
			throw new OrganizationAdministrationInvariantViolation("Tenant already has its V1 workspace");
		}
		UUID id = UUID.randomUUID();
		if (port.createWorkspace(context.tenantId().toString(), id, safeName, safeSlug, Instant.now()) == 0) throw new ConcurrencyConflictException();
		port.createWorkspaceMembership(context.tenantId().toString(), id.toString(), context.userId().value(), context.roles().stream().filter(role -> role != MembershipRole.BUYER).map(Enum::name).collect(Collectors.toUnmodifiableSet()), Instant.now());
		if (port.saveWorkspaceIdempotency(context.tenantId().toString(), idempotencyKey, requestHash, id) == 0) {
			WorkspaceSummary existing = port.findWorkspaceIdempotent(context.tenantId().toString(), idempotencyKey, requestHash)
					.flatMap(value -> port.findWorkspace(context.tenantId().toString(), value.toString())).orElseThrow(() -> new ConcurrencyConflictException());
			return new OrganizationAdministrationResult<>(existing, existing.version());
		}
		appendAudit(context, "WORKSPACE_CREATED", correlationId, java.util.Map.of("workspaceId", id.toString()));
		publishChange(context, "workspace", id.toString(), "organization.workspace.updated", "ACTIVE");
		WorkspaceSummary value = port.findWorkspace(context.tenantId().toString(), id.toString()).orElseThrow(() -> new ApiResourceNotFoundException("workspace"));
		return new OrganizationAdministrationResult<>(value, value.version());
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceSummary> updateWorkspace(CurrentAccessContext context, String workspaceId,
			String name, String slug, WorkspaceStatus status, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		var current = findWorkspace(context, workspaceId);
		if (name == null || name.isBlank()) name = current.name();
		if (slug == null || slug.isBlank()) slug = current.slug(); else slug = new WorkspaceSlug(slug).value();
		if (status == null) status = WorkspaceStatus.from(current.status());
		if (!status.name().equals(current.status())) throw new OrganizationAdministrationInvariantViolation("Workspace lifecycle changes require a command endpoint");
		if (port.updateWorkspace(context.tenantId().toString(), current.id(), name.trim(), slug, status.name(), expectedVersion) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "WORKSPACE_UPDATED", correlationId, java.util.Map.of("workspaceId", current.id()));
		publishChange(context, "workspace", current.id(), "organization.workspace.updated", status.name());
		return new OrganizationAdministrationResult<>(findWorkspace(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceSummary> suspendWorkspace(CurrentAccessContext context, String workspaceId, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		WorkspaceSummary current = findWorkspace(context, workspaceId);
		port.lockTenant(context.tenantId().toString());
		if ("ACTIVE".equals(current.status()) && port.activeAdministrativeWorkspaceCount(context.tenantId().toString()) <= 1) throw new OrganizationAdministrationInvariantViolation("At least one usable administrative workspace must remain");
		if (port.updateWorkspaceStatus(context.tenantId().toString(), current.id(), WorkspaceStatus.SUSPENDED.name(), expectedVersion) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "WORKSPACE_SUSPENDED", correlationId, java.util.Map.of("workspaceId", current.id()));
		publishChange(context, "workspace", current.id(), "organization.workspace.updated", WorkspaceStatus.SUSPENDED.name());
		return new OrganizationAdministrationResult<>(findWorkspace(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceSummary> reactivateWorkspace(CurrentAccessContext context, String workspaceId, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		WorkspaceSummary current = findWorkspace(context, workspaceId);
		if (port.updateWorkspaceStatus(context.tenantId().toString(), current.id(), WorkspaceStatus.ACTIVE.name(), expectedVersion) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "WORKSPACE_REACTIVATED", correlationId, java.util.Map.of("workspaceId", current.id()));
		publishChange(context, "workspace", current.id(), "organization.workspace.updated", WorkspaceStatus.ACTIVE.name());
		return new OrganizationAdministrationResult<>(findWorkspace(context, current.id()), expectedVersion + 1);
	}

	@Override
	public List<WorkspaceMembershipSummary> memberships(CurrentAccessContext context) {
		read(context); return port.findMemberships(context.tenantId().toString());
	}

	@Override
	public WorkspaceMembershipSummary membership(CurrentAccessContext context, String membershipId) {
		read(context); return findMembership(context, membershipId);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> changeRoles(CurrentAccessContext context, String membershipId,
			Set<MembershipRole> roles, long expectedVersion, String correlationId) {
		manageRoleAssignments(context);
		var current = findMembership(context, membershipId);
		Set<MembershipRole> before = parseRoles(current);
		if (roles == null || roles.isEmpty() || roles.contains(MembershipRole.BUYER) || before.contains(MembershipRole.BUYER)) {
			throw new OrganizationAdministrationInvariantViolation("Cross-surface role conversion is not allowed");
		}
		if (before.contains(MembershipRole.TENANT_ADMIN) && !roles.contains(MembershipRole.TENANT_ADMIN)) {
			port.lockTenant(context.tenantId().toString());
			if (port.activeTenantAdminCount(current.workspaceId()) <= 1) throw new OrganizationAdministrationInvariantViolation("At least one active tenant admin must remain");
		}
		guardCompanyOwnerTransition(context.tenantId().toString(), before.contains(MembershipRole.COMPANY_OWNER), roles.contains(MembershipRole.COMPANY_OWNER));
		if (AssignableRolePolicy.isCompanyOwner(context.roleCodes()) && !AssignableRolePolicy.isTenantAdmin(context.roleCodes())
				&& (before.contains(MembershipRole.TENANT_ADMIN) || before.contains(MembershipRole.COMPANY_OWNER)
						|| roles.contains(MembershipRole.TENANT_ADMIN) || roles.contains(MembershipRole.COMPANY_OWNER))) {
			throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("COMPANY_OWNER cannot modify reserved technical assignments");
		}
		AssignableRoleEnvelope.internalMembership().requireAssignable(roles);
		for (MembershipRole role : roles) AssignableRolePolicy.requireCanAssign(context.roleCodes(), context.permissionCodes(), RoleCatalog.definitionFor(role));
		Set<String> roleNames = roles.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
		if (port.updateRoles(context.tenantId().toString(), current.id(), roleNames, expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("ROLE_CHANGED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(),
				String.join(",", before.stream().map(Enum::name).sorted().toList()), current.status(), String.join(",", roleNames.stream().sorted().toList()), current.status(), correlationId);
		audit(context, "ROLE_ASSIGNMENT_CHANGED", current.id(), correlationId, java.util.Map.of("beforeRoles", before.stream().map(Enum::name).sorted().toList(), "afterRoles", roleNames.stream().sorted().toList()));
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> changeRoleDefinitions(CurrentAccessContext context,
			String membershipId, Set<String> roleDefinitionIds, long expectedVersion, String correlationId) {
		if (context.surface() != com.nexa.api.tenantmanagement.domain.model.access.Surface.PLATFORM) {
			throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("Role assignment is not allowed on this surface");
		}
		if (roleDefinitionIds == null || roleDefinitionIds.isEmpty()) throw new OrganizationAdministrationInvariantViolation("At least one role assignment is required");
		WorkspaceMembershipSummary current = findMembership(context, membershipId);
		List<RoleDefinition> targets = roleDefinitionIds.stream().map(id -> resolveRoleDefinition(context, id)).toList();
		if (AssignableRolePolicy.isCompanyOwner(context.roleCodes()) && !AssignableRolePolicy.isTenantAdmin(context.roleCodes())
				&& (containsRole(current, MembershipRole.TENANT_ADMIN) || containsRole(current, MembershipRole.COMPANY_OWNER))) {
			throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("COMPANY_OWNER cannot modify reserved technical assignments");
		}
		for (RoleDefinition target : targets) {
			AssignableRolePolicy.requireCanAssign(context.roleCodes(), context.permissionCodes(), target);
			if ("BUYER".equalsIgnoreCase(target.code())) throw new OrganizationAdministrationInvariantViolation("Buyer cannot be assigned to an internal membership");
		}
		Set<String> targetCodes = targets.stream().map(RoleDefinition::code).collect(Collectors.toUnmodifiableSet());
		guardCompanyOwnerTransition(context.tenantId().toString(), containsRole(current, MembershipRole.COMPANY_OWNER), targetCodes.stream().anyMatch(code -> "COMPANY_OWNER".equalsIgnoreCase(code)));
		if (containsRole(current, MembershipRole.TENANT_ADMIN)
				&& targetCodes.stream().noneMatch(code -> "TENANT_ADMIN".equalsIgnoreCase(code))) {
			port.lockTenant(context.tenantId().toString());
			if (port.activeTenantAdminCount(current.workspaceId()) <= 1) throw new OrganizationAdministrationInvariantViolation("At least one active tenant admin must remain");
		}
		try {
			if (port.updateRoleDefinitionAssignments(context.tenantId().toString(), current.id(), roleDefinitionIds, expectedVersion) == 0) {
				throw new ConcurrencyConflictException();
			}
		} catch (UnsupportedOperationException exception) {
			throw new com.nexa.api.tenantmanagement.application.exception.RoleDefinitionPersistenceUnavailableException();
		}
		port.appendMembershipEvent("ROLE_DEFINITION_ASSIGNMENT_CHANGED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(),
				String.join(",", current.roleDefinitionIds()), current.status(), String.join(",", roleDefinitionIds), current.status(), correlationId);
		audit(context, "ROLE_DEFINITION_ASSIGNMENT_CHANGED", current.id(), correlationId, java.util.Map.of("beforeRoleDefinitionIds", current.roleDefinitionIds(), "afterRoleDefinitionIds", roleDefinitionIds));
		publishChange(context, "membership", current.id(), "organization.membership.role-definition-changed", current.status());
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> suspendMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_MEMBER_MANAGE);
		var current = findMembership(context, membershipId);
		if ("DISABLED".equals(current.status())) {
			if (current.version() != expectedVersion) throw new ConcurrencyConflictException();
			return new OrganizationAdministrationResult<>(current, current.version());
		}
		if (containsRole(current, MembershipRole.TENANT_ADMIN)) { port.lockTenant(context.tenantId().toString()); if (port.activeTenantAdminCount(current.workspaceId()) <= 1) throw new OrganizationAdministrationInvariantViolation("At least one active tenant admin must remain"); }
		if (containsRole(current, MembershipRole.COMPANY_OWNER)) { port.lockTenant(context.tenantId().toString()); if (port.activeCompanyOwnerCount(context.tenantId().toString()) <= 1) throw new OrganizationAdministrationInvariantViolation("At least one active company owner must remain"); }
		if (port.updateStatus(context.tenantId().toString(), current.id(), "DISABLED", expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("MEMBERSHIP_SUSPENDED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(), String.join(",", current.roles()), current.status(), String.join(",", current.roles()), "DISABLED", correlationId);
		audit(context, "MEMBERSHIP_SUSPENDED", current.id(), correlationId, java.util.Map.of("status", "DISABLED"));
		publishChange(context, "membership", current.id(), "organization.membership.suspended", "DISABLED");
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	@Override
	public OrganizationAdministrationResult<WorkspaceMembershipSummary> reactivateMembership(CurrentAccessContext context, String membershipId,
			long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_MEMBER_MANAGE);
		var current = findMembership(context, membershipId);
		if ("ACTIVE".equals(current.status())) {
			if (current.version() != expectedVersion) throw new ConcurrencyConflictException();
			return new OrganizationAdministrationResult<>(current, current.version());
		}
		if (port.updateStatus(context.tenantId().toString(), current.id(), "ACTIVE", expectedVersion) == 0) throw new ConcurrencyConflictException();
		port.appendMembershipEvent("MEMBERSHIP_REACTIVATED", context.tenantId().toString(), current.workspaceId(), current.id(), context.membershipId().toString(), String.join(",", current.roles()), current.status(), String.join(",", current.roles()), "ACTIVE", correlationId);
		audit(context, "MEMBERSHIP_REACTIVATED", current.id(), correlationId, java.util.Map.of("status", "ACTIVE"));
		publishChange(context, "membership", current.id(), "organization.membership.reactivated", "ACTIVE");
		return new OrganizationAdministrationResult<>(findMembership(context, current.id()), expectedVersion + 1);
	}

	private WorkspaceSummary findWorkspace(CurrentAccessContext context, String id) {
		try { return port.findWorkspace(context.tenantId().toString(), new WorkspaceId(id).toString()).orElseThrow(() -> new ApiResourceNotFoundException("workspace")); }
		catch (TenantManagementInvariantViolation exception) { throw new ApiResourceNotFoundException("workspace"); }
	}
	private WorkspaceMembershipSummary findMembership(CurrentAccessContext context, String id) {
		try {
			return port.findMembership(context.tenantId().toString(), id).orElseThrow(() -> new ApiResourceNotFoundException("membership"));
		} catch (TenantManagementInvariantViolation exception) {
			throw new ApiResourceNotFoundException("membership");
		}
	}
	private static Set<MembershipRole> parseRoles(WorkspaceMembershipSummary value) {
		java.util.EnumSet<MembershipRole> roles = java.util.EnumSet.noneOf(MembershipRole.class);
		roles.addAll(value.roles().stream().map(MembershipRole::from).collect(Collectors.toSet()));
		for (MembershipRole role : MembershipRole.values()) {
			if (value.roleDefinitionIds().stream().anyMatch(id -> id.equalsIgnoreCase(RoleDefinitionId.system(role.name()).toString()))) {
				roles.add(role);
			}
		}
		return java.util.Set.copyOf(roles);
	}
	private void guardCompanyOwnerTransition(String tenantId, boolean beforeOwner, boolean afterOwner) {
		if (beforeOwner == afterOwner) return;
		port.lockTenant(tenantId);
		int activeOwners = port.activeCompanyOwnerCount(tenantId);
		if (!beforeOwner && afterOwner && activeOwners >= 1) {
			throw new OrganizationAdministrationInvariantViolation("Exactly one active company owner is required");
		}
		if (beforeOwner && !afterOwner && activeOwners <= 1) {
			throw new OrganizationAdministrationInvariantViolation("At least one active company owner must remain");
		}
	}
	private RoleDefinition resolveRoleDefinition(CurrentAccessContext context, String rawId) {
		try {
			RoleDefinitionId id = new RoleDefinitionId(rawId);
			for (MembershipRole role : MembershipRole.values()) if (role.definition().id().equals(id)) return role.definition();
			if (roleDefinitions == null) throw new com.nexa.api.tenantmanagement.application.exception.RoleDefinitionPersistenceUnavailableException();
			RoleDefinition value = roleDefinitions.findById(id).orElseThrow(() -> new ApiResourceNotFoundException("role-definition"));
			if (value.tenantId() == null || !value.tenantId().equals(context.tenantId())
					|| value.workspaceId() != null && !value.workspaceId().equals(context.workspaceId())) {
				throw new ApiResourceNotFoundException("role-definition");
			}
			return value;
		} catch (IllegalArgumentException exception) {
			throw new ApiResourceNotFoundException("role-definition");
		}
	}
	private static boolean containsRole(WorkspaceMembershipSummary membership, MembershipRole role) {
		return membership.roleDefinitionIds().stream().anyMatch(id -> id.equalsIgnoreCase(RoleDefinitionId.system(role.name()).toString()))
				|| membership.roles().stream().anyMatch(value -> value.equalsIgnoreCase(role.name()));
	}
	private static void read(CurrentAccessContext context) { context.requirePermission(Permission.TENANT_READ); }
	private static void manageRoleAssignments(CurrentAccessContext context) {
		if (!context.allows(PermissionKey.TENANT_ROLE_ASSIGN)) {
			throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("Membership role assignment is not allowed");
		}
	}
	private void audit(CurrentAccessContext context, String type, String targetMembershipId, String correlationId, java.util.Map<String, Object> metadata) {
		audit.append(new SecurityAuditPort.Event(type, context.userId().value(), java.util.UUID.fromString(targetMembershipId),
				context.tenantId().value(), context.workspaceId().value(), context.surface().name(), correlationId == null ? "unknown" : correlationId, "unknown", java.time.Instant.now(), metadata));
	}
	private void appendAudit(CurrentAccessContext context, String type, String correlationId, java.util.Map<String, Object> metadata) {
		audit.append(new SecurityAuditPort.Event(type, context.userId().value(), null, context.tenantId().value(), context.workspaceId().value(), context.surface().name(), correlationId == null ? "unknown" : correlationId, "unknown", java.time.Instant.now(), metadata));
	}

	private void publishChange(CurrentAccessContext context, String aggregateType, String aggregateId, String eventType, String publicStatus) {
		changes.append(context.tenantId().toString(), context.workspaceId().toString(), null, aggregateType, aggregateId,
				eventType, publicStatus, Instant.now().toEpochMilli(), false);
	}

	public static final class ConcurrencyConflictException extends RuntimeException { }
	public static final class IdempotencyKeyRequiredException extends RuntimeException { }
	public static final class IdempotencyPayloadConflictException extends RuntimeException { }

	private static String sha256(String value) {
		try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (Exception exception) { throw new IllegalStateException("Unable to hash workspace idempotency payload", exception); }
	}
}
