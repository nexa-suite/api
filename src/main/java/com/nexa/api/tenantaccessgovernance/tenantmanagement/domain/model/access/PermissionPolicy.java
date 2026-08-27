package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.EnumSet;

/**
 * Canonical role-to-permission policy. Adapters and tokens must consume this mapping instead of defining another one.
 */
public final class PermissionPolicy {
	private static final Map<MembershipRole, Set<Permission>> PERMISSIONS_BY_ROLE = Map.of(
			MembershipRole.TENANT_ADMIN, Set.of(
					Permission.TENANT_READ, Permission.TENANT_MANAGE,
					Permission.IAM_USER_READ, Permission.IAM_USER_MANAGE),
			MembershipRole.COMPANY_OWNER, Set.of(
					Permission.TENANT_READ, Permission.OWNER_DASHBOARD_READ,
					Permission.SALES_READ, Permission.WAREHOUSE_READ, Permission.LOGISTICS_READ,
					Permission.CATALOG_READ, Permission.CATALOG_MANAGE, Permission.CATALOG_PRICE_MANAGE,
					Permission.PROMOTION_READ, Permission.PROMOTION_MANAGE),
			MembershipRole.SALES, Set.of(
					Permission.CATALOG_READ,
					Permission.PROMOTION_READ, Permission.SALES_READ, Permission.SALES_WRITE),
			MembershipRole.WAREHOUSE, Set.of(
					Permission.CATALOG_READ, Permission.WAREHOUSE_READ, Permission.WAREHOUSE_WRITE,
					Permission.FULFILLMENT_READ),
			MembershipRole.LOGISTICS, Set.of(
					Permission.CATALOG_READ, Permission.PROMOTION_READ,
					Permission.WAREHOUSE_READ, Permission.LOGISTICS_READ, Permission.LOGISTICS_WRITE,
					Permission.FULFILLMENT_READ),
			MembershipRole.BUYER, Set.of(
					Permission.CATALOG_READ, Permission.SALES_BUYER_READ, Permission.SALES_BUYER_WRITE,
					Permission.PROMOTION_READ, Permission.ORDERS_BUYER_READ, Permission.TRACKING_BUYER_READ));

	private PermissionPolicy() {
	}

	public static Set<Permission> permissionsFor(MembershipRole role) {
		Set<Permission> permissions = PERMISSIONS_BY_ROLE.get(Objects.requireNonNull(role, "Membership role is required"));
		if (permissions == null) throw new AccessPolicyViolation("No permission catalog entry exists for membership role");
		return permissions;
	}

	public static Set<Permission> permissionsFor(Set<MembershipRole> roles) {
		Objects.requireNonNull(roles, "Membership roles are required");
		if (roles.isEmpty()) throw new AccessPolicyViolation("At least one membership role is required");
		EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
		roles.forEach(role -> permissions.addAll(permissionsFor(role)));
		return Set.copyOf(new LinkedHashSet<>(permissions));
	}

	public static boolean allows(MembershipRole role, Permission permission) {
		return permissionsFor(role).contains(Objects.requireNonNull(permission, "Permission is required"));
	}

	public static void require(MembershipRole role, Permission permission) {
		if (!allows(role, permission)) {
			throw new AccessPolicyViolation("Membership role does not have the requested permission");
		}
	}

	public static void require(Set<MembershipRole> roles, Permission permission) {
		if (!permissionsFor(roles).contains(Objects.requireNonNull(permission))) {
			throw new AccessPolicyViolation("Membership roles do not have the requested permission");
		}
	}
}
