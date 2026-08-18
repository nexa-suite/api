package com.nexa.api.tenantmanagement.domain.model.access;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** Explicit anti-privilege-escalation policy for role administration. */
public final class AssignableRolePolicy {
	private AssignableRolePolicy() { }

	public static boolean isTenantAdmin(Collection<String> roleCodes) {
		return containsRole(roleCodes, "TENANT_ADMIN");
	}

	public static boolean isCompanyOwner(Collection<String> roleCodes) {
		return containsRole(roleCodes, "COMPANY_OWNER");
	}

	public static void requireCanRead(Collection<String> actorRoles, Collection<String> actorPermissions) {
		if (!isTenantAdmin(actorRoles) && !isCompanyOwner(actorRoles)
				&& !containsPermission(actorPermissions, PermissionKey.TENANT_ROLE_READ)) {
			throw new AccessPolicyViolation("Role definitions are not readable by this membership");
		}
	}

	public static void requireCanManageDefinitions(Collection<String> actorRoles) {
		if (!isTenantAdmin(actorRoles)) {
			throw new AccessPolicyViolation("Only TENANT_ADMIN can manage role definitions");
		}
	}

	public static void requireCanAssign(Collection<String> actorRoles, Collection<String> actorPermissions,
			RoleDefinition target) {
		Objects.requireNonNull(target, "Target role definition is required");
		if (isTenantAdmin(actorRoles)) return;
		if (!isCompanyOwner(actorRoles)) throw new AccessPolicyViolation("Role assignment is not allowed");
		if (target.type() != RoleDefinitionType.CUSTOM && target.type() != RoleDefinitionType.SYSTEM_TEMPLATE) {
			throw new AccessPolicyViolation("COMPANY_OWNER cannot assign reserved technical roles");
		}
		if (!PermissionCatalog.companyOwnerAssignableEnvelope().containsAll(target.permissions())) {
			throw new AccessPolicyViolation("Role permissions exceed the COMPANY_OWNER assignment envelope");
		}
	}

	public static void requireWithinAssignableEnvelope(Collection<String> actorRoles, Set<PermissionKey> permissions) {
		Objects.requireNonNull(permissions, "Role permissions are required");
		if (isTenantAdmin(actorRoles)) return;
		if (!isCompanyOwner(actorRoles) || !PermissionCatalog.companyOwnerAssignableEnvelope().containsAll(permissions)) {
			throw new AccessPolicyViolation("Requested role permissions exceed the actor assignment envelope");
		}
	}

	public static boolean containsPermission(Collection<String> permissions, PermissionKey expected) {
		return permissions != null && permissions.stream().filter(Objects::nonNull).anyMatch(expected::matches);
	}

	private static boolean containsRole(Collection<String> roleCodes, String expected) {
		return roleCodes != null && roleCodes.stream().filter(Objects::nonNull)
				.anyMatch(value -> expected.equalsIgnoreCase(value.trim()));
	}
}
