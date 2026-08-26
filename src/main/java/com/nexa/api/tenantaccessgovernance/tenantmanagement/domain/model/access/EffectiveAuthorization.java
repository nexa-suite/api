package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable server-side authorization snapshot for one tenant membership. */
public record EffectiveAuthorization(Set<String> roleDefinitionIds, Set<String> roleCodes,
		Set<String> permissionCodes, long authorizationVersion) {
	public EffectiveAuthorization {
		roleDefinitionIds = normalize(roleDefinitionIds, "Role definition ids", false);
		roleCodes = normalize(roleCodes, "Role codes", true);
		permissionCodes = normalize(permissionCodes, "Permission codes", false);
		if (authorizationVersion < 0) throw new IllegalArgumentException("Authorization version cannot be negative");
	}

	public static EffectiveAuthorization fixed(Collection<MembershipRole> roles, long authorizationVersion) {
		Objects.requireNonNull(roles, "Membership roles are required");
		if (roles.isEmpty()) throw new AccessPolicyViolation("At least one membership role is required");
		Set<PermissionKey> keys = PermissionCatalog.forBuiltInRoles(roles);
		LinkedHashSet<String> roleCodes = roles.stream().map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
		LinkedHashSet<String> roleIds = roles.stream().map(role -> RoleDefinitionId.system(role.name()).toString())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		LinkedHashSet<String> permissions = new LinkedHashSet<>(PermissionCatalog.compatibilityCodes(keys));
		return new EffectiveAuthorization(roleIds, roleCodes, permissions, authorizationVersion);
	}

	public static EffectiveAuthorization of(Collection<RoleDefinition> definitions, Collection<MembershipRole> fixedRoles,
			long authorizationVersion) {
		Objects.requireNonNull(definitions, "Role definitions are required");
		Objects.requireNonNull(fixedRoles, "Fixed roles are required");
		LinkedHashSet<String> roleIds = new LinkedHashSet<>();
		LinkedHashSet<String> roleCodes = new LinkedHashSet<>();
		java.util.EnumSet<PermissionKey> keys = java.util.EnumSet.noneOf(PermissionKey.class);
		for (MembershipRole role : fixedRoles) {
			roleIds.add(RoleDefinitionId.system(role.name()).toString());
			roleCodes.add(role.name());
			keys.addAll(PermissionCatalog.forBuiltInRole(role));
		}
		for (RoleDefinition definition : definitions) {
			if (definition.status() != RoleDefinitionStatus.ACTIVE) continue;
			roleIds.add(definition.id().toString());
			roleCodes.add(apiRoleCode(definition));
			keys.addAll(definition.permissions());
		}
		if (roleCodes.isEmpty()) throw new AccessPolicyViolation("At least one active role is required");
		LinkedHashSet<String> permissions = new LinkedHashSet<>(PermissionCatalog.compatibilityCodes(keys));
		return new EffectiveAuthorization(roleIds, roleCodes, permissions, authorizationVersion);
	}

	/** Runtime authority: only persisted RoleDefinition/RolePermission assignments are evaluated. */
	public static EffectiveAuthorization canonical(Collection<RoleDefinition> definitions, long authorizationVersion) {
		Objects.requireNonNull(definitions, "Role definitions are required");
		LinkedHashSet<String> roleIds = new LinkedHashSet<>();
		LinkedHashSet<String> roleCodes = new LinkedHashSet<>();
		java.util.EnumSet<PermissionKey> keys = java.util.EnumSet.noneOf(PermissionKey.class);
		for (RoleDefinition definition : definitions) {
			if (definition.status() != RoleDefinitionStatus.ACTIVE) continue;
			roleIds.add(definition.id().toString());
			roleCodes.add(apiRoleCode(definition));
			keys.addAll(definition.permissions());
		}
		if (roleCodes.isEmpty()) throw new AccessPolicyViolation("At least one active canonical role is required");
		return new EffectiveAuthorization(roleIds, roleCodes, PermissionCatalog.compatibilityCodes(keys), authorizationVersion);
	}

	public boolean allows(PermissionKey permission) { return permission != null && permissionCodes.contains(permission.code()); }

	public boolean allowsLegacy(Permission permission) {
		if (permission == null) return false;
		return switch (permission) {
			case TENANT_READ -> hasAny("tenant.organization.read", "tenant.workspace.read", "tenant.member.read", "tenant.role.read");
			case TENANT_MANAGE -> hasAny("tenant.organization.manage", "tenant.workspace.manage");
			case IAM_USER_READ -> hasAny("tenant.member.read");
			case IAM_USER_MANAGE -> hasAny("tenant.member.manage");
			case CATALOG_MANAGE -> hasAny("catalog.product.manage", "catalog.taxonomy.manage", "catalog.promotion.manage");
			case SALES_READ -> hasAny("sales.dashboard.read", "sales.purchase_request.read", "sales.order.read", "client.read");
			case SALES_WRITE -> hasAny("sales.purchase_request.review", "sales.order.create_manual", "sales.order.manage", "client.manage");
			case WAREHOUSE_READ -> hasAny("warehouse.read", "inventory.read");
			case WAREHOUSE_WRITE -> hasAny("warehouse.location.manage", "inventory.receive", "inventory.adjust", "inventory.reserve", "inventory.release", "inventory.waste");
			case FULFILLMENT_READ -> hasAny("fulfillment.read");
			case LOGISTICS_READ -> hasAny("logistics.read", "dispatch.read", "logistics.analytics.read");
			case LOGISTICS_WRITE -> hasAny("dispatch.assign", "dispatch.schedule", "dispatch.start_route", "dispatch.temperature", "dispatch.incident", "dispatch.reprogram", "dispatch.complete");
			case CATALOG_READ -> hasAny("catalog.read");
			case CATALOG_PRICE_MANAGE -> hasAny("catalog.price.manage");
			case PROMOTION_READ -> hasAny("catalog.promotion.read");
			case PROMOTION_MANAGE -> hasAny("catalog.promotion.manage");
			case OWNER_DASHBOARD_READ -> hasAny("sales.dashboard.read");
			case SALES_BUYER_READ -> hasAny("buyer.sales.read");
			case SALES_BUYER_WRITE -> hasAny("buyer.sales.write");
			case ORDERS_BUYER_READ -> hasAny("buyer.order.read");
			case TRACKING_BUYER_READ -> hasAny("buyer.tracking.read");
			default -> permissionCodes.contains(permission.code());
		};
	}

	public boolean isBuyerOnly() {
		return roleCodes.size() == 1 && roleCodes.contains(MembershipRole.BUYER.name());
	}

	public boolean allowsSurface(Surface surface) {
		if (surface == null) return false;
		return surface == Surface.PORTAL ? isBuyerOnly() : !roleCodes.contains(MembershipRole.BUYER.name());
	}

	private boolean hasAny(String... codes) {
		return java.util.Arrays.stream(codes).anyMatch(permissionCodes::contains);
	}

	private static Set<String> normalize(Set<String> values, String label, boolean preserveCase) {
		if (values == null) throw new IllegalArgumentException(label + " are required");
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " cannot contain blank values");
			normalized.add(preserveCase ? value.trim() : value.trim().toLowerCase(java.util.Locale.ROOT));
		}
		return Set.copyOf(normalized);
	}

	private static String apiRoleCode(RoleDefinition definition) {
		return java.util.Arrays.stream(MembershipRole.values())
				.filter(role -> role.name().equalsIgnoreCase(definition.code()))
				.map(Enum::name)
				.findFirst()
				.orElse(definition.code());
	}
}
