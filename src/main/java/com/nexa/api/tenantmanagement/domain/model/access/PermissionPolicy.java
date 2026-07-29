package com.nexa.api.tenantmanagement.domain.model.access;

import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical role-to-permission policy. Adapters and tokens must consume this mapping instead of defining another one.
 */
public final class PermissionPolicy {
	private static final Set<Permission> COMPANY_OWNER_PERMISSIONS = Set.copyOf(java.util.EnumSet.allOf(Permission.class));

	private static final Map<MembershipRole, Set<Permission>> PERMISSIONS_BY_ROLE = Map.of(
			MembershipRole.COMPANY_OWNER, COMPANY_OWNER_PERMISSIONS,
			MembershipRole.SALES, Set.of(
					Permission.CATALOG_READ, Permission.SALES_READ, Permission.SALES_WRITE,
					Permission.TENANT_READ, Permission.INVOICING_READ, Permission.LOGISTICS_READ),
			MembershipRole.WAREHOUSE, Set.of(
					Permission.CATALOG_READ, Permission.WAREHOUSE_READ, Permission.WAREHOUSE_WRITE,
					Permission.LOGISTICS_READ),
			MembershipRole.LOGISTICS, Set.of(
					Permission.CATALOG_READ, Permission.LOGISTICS_READ, Permission.LOGISTICS_WRITE,
					Permission.WAREHOUSE_READ),
			MembershipRole.BUYER, Set.of(
					Permission.CATALOG_READ, Permission.SALES_BUYER_READ, Permission.SALES_BUYER_WRITE,
					Permission.LOGISTICS_BUYER_READ, Permission.INVOICING_BUYER_READ));

	private PermissionPolicy() {
	}

	public static Set<Permission> permissionsFor(MembershipRole role) {
		return PERMISSIONS_BY_ROLE.get(Objects.requireNonNull(role, "Membership role is required"));
	}

	public static boolean allows(MembershipRole role, Permission permission) {
		return permissionsFor(role).contains(Objects.requireNonNull(permission, "Permission is required"));
	}

	public static void require(MembershipRole role, Permission permission) {
		if (!allows(role, permission)) {
			throw new AccessPolicyViolation("Membership role does not have the requested permission");
		}
	}
}
