package com.nexa.api.tenantmanagement.domain.model.access;

import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single role-to-surface boundary. MembershipRole remains the source of role ownership.
 */
public final class RoleSurfacePolicy {
	private static final Map<MembershipRole, Set<Surface>> ALLOWED_SURFACES = Map.of(
			MembershipRole.TENANT_ADMIN, Set.of(Surface.PLATFORM),
			MembershipRole.COMPANY_OWNER, Set.of(Surface.PLATFORM),
			MembershipRole.SALES, Set.of(Surface.PLATFORM),
			MembershipRole.WAREHOUSE, Set.of(Surface.PLATFORM),
			MembershipRole.LOGISTICS, Set.of(Surface.PLATFORM),
			MembershipRole.BUYER, Set.of(Surface.PORTAL));

	private RoleSurfacePolicy() {
	}

	public static Set<Surface> allowedSurfaces(MembershipRole role) {
		return ALLOWED_SURFACES.get(Objects.requireNonNull(role, "Membership role is required"));
	}

	public static boolean allows(MembershipRole role, Surface surface) {
		return allowedSurfaces(role).contains(Objects.requireNonNull(surface, "Surface is required"));
	}

	public static boolean allows(Set<MembershipRole> roles, Surface surface) {
		Objects.requireNonNull(roles, "Membership roles are required");
		return roles.stream().anyMatch(role -> allows(role, surface));
	}

	public static void requireAllowed(MembershipRole role, Surface surface) {
		if (!allows(role, surface)) {
			throw new AccessPolicyViolation("Membership role is not allowed on the requested surface");
		}
	}
}
