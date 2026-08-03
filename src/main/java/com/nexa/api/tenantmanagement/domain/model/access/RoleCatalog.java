package com.nexa.api.tenantmanagement.domain.model.access;

import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Canonical role definitions, surfaces and assignment boundaries. */
public final class RoleCatalog {
	private static final Instant CATALOG_TIME = Instant.EPOCH;
	private static final Map<MembershipRole, RoleDefinition> DEFINITIONS = Map.of(
			MembershipRole.TENANT_ADMIN, RoleDefinition.systemReserved(MembershipRole.TENANT_ADMIN, CATALOG_TIME),
			MembershipRole.COMPANY_OWNER, RoleDefinition.systemReserved(MembershipRole.COMPANY_OWNER, CATALOG_TIME),
			MembershipRole.SALES, RoleDefinition.systemTemplate(MembershipRole.SALES, CATALOG_TIME),
			MembershipRole.WAREHOUSE, RoleDefinition.systemTemplate(MembershipRole.WAREHOUSE, CATALOG_TIME),
			MembershipRole.LOGISTICS, RoleDefinition.systemTemplate(MembershipRole.LOGISTICS, CATALOG_TIME),
			MembershipRole.BUYER, RoleDefinition.systemTemplate(MembershipRole.BUYER, CATALOG_TIME));

	private static final Set<MembershipRole> INTERNAL_ASSIGNABLE = Set.of(
			MembershipRole.TENANT_ADMIN,
			MembershipRole.COMPANY_OWNER,
			MembershipRole.SALES,
			MembershipRole.WAREHOUSE,
			MembershipRole.LOGISTICS);

	private RoleCatalog() {
	}

	public static RoleDefinition definitionFor(MembershipRole role) {
		return DEFINITIONS.get(java.util.Objects.requireNonNull(role, "Membership role is required"));
	}

	public static Surface surfaceFor(MembershipRole role) {
		return role == MembershipRole.BUYER ? Surface.PORTAL : Surface.PLATFORM;
	}

	public static Set<MembershipRole> internalAssignableRoles() {
		return INTERNAL_ASSIGNABLE;
	}

	public static AssignableRoleEnvelope internalAssignableEnvelope() {
		return AssignableRoleEnvelope.internalMembership();
	}
}
