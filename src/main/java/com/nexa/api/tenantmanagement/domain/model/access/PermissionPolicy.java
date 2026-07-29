package com.nexa.api.tenantmanagement.domain.model.access;

import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical role-to-permission policy. Adapters and tokens must consume this mapping instead of defining another one.
 */
public final class PermissionPolicy {
	private static final Set<Permission> COMPANY_OWNER_PERMISSIONS = Set.of(
			Permission.TENANT_READ, Permission.TENANT_WRITE,
			Permission.WORKSPACE_READ, Permission.WORKSPACE_WRITE,
			Permission.MEMBERSHIP_READ, Permission.MEMBERSHIP_WRITE,
			Permission.CATALOG_READ, Permission.CATALOG_WRITE,
			Permission.SALES_READ, Permission.SALES_WRITE,
			Permission.ORDERS_READ, Permission.ORDERS_WRITE,
			Permission.DOCUMENTS_READ, Permission.DOCUMENTS_WRITE,
			Permission.WAREHOUSE_READ, Permission.WAREHOUSE_WRITE,
			Permission.INVENTORY_READ, Permission.INVENTORY_WRITE,
			Permission.LOGISTICS_READ, Permission.LOGISTICS_WRITE,
			Permission.SHIPMENTS_READ, Permission.SHIPMENTS_WRITE,
			Permission.DISPATCH_READ, Permission.DISPATCH_WRITE,
			Permission.PORTAL_READ, Permission.PORTAL_WRITE,
			Permission.REQUESTS_READ, Permission.REQUESTS_WRITE,
			Permission.ANALYTICS_READ);

	private static final Map<MembershipRole, Set<Permission>> PERMISSIONS_BY_ROLE = Map.of(
			MembershipRole.COMPANY_OWNER, COMPANY_OWNER_PERMISSIONS,
			MembershipRole.SALES, Set.of(
					Permission.CATALOG_READ, Permission.CATALOG_WRITE,
					Permission.SALES_READ, Permission.SALES_WRITE,
					Permission.ORDERS_READ, Permission.ORDERS_WRITE,
					Permission.DOCUMENTS_READ, Permission.DOCUMENTS_WRITE),
			MembershipRole.WAREHOUSE, Set.of(
					Permission.WAREHOUSE_READ, Permission.WAREHOUSE_WRITE,
					Permission.INVENTORY_READ, Permission.INVENTORY_WRITE),
			MembershipRole.LOGISTICS, Set.of(
					Permission.WAREHOUSE_READ,
					Permission.LOGISTICS_READ, Permission.LOGISTICS_WRITE,
					Permission.SHIPMENTS_READ, Permission.SHIPMENTS_WRITE,
					Permission.DISPATCH_READ, Permission.DISPATCH_WRITE),
			MembershipRole.BUYER, Set.of(
					Permission.PORTAL_READ, Permission.PORTAL_WRITE,
					Permission.REQUESTS_READ, Permission.REQUESTS_WRITE,
					Permission.DOCUMENTS_READ));

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
