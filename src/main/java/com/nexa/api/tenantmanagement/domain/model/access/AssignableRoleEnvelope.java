package com.nexa.api.tenantmanagement.domain.model.access;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Allow-list for role assignments on internal workspace memberships.
 * Buyer is a different surface and cannot be smuggled into an internal
 * assignment through a role patch or invitation.
 */
public final class AssignableRoleEnvelope {
	private final Set<MembershipRole> allowedRoles;

	private AssignableRoleEnvelope(Set<MembershipRole> allowedRoles) {
		if (allowedRoles == null || allowedRoles.isEmpty() || allowedRoles.stream().anyMatch(Objects::isNull)) {
			throw new TenantManagementInvariantViolation("Assignable role envelope is empty");
		}
		this.allowedRoles = Collections.unmodifiableSet(EnumSet.copyOf(allowedRoles));
	}

	public static AssignableRoleEnvelope internalMembership() {
		return new AssignableRoleEnvelope(RoleCatalog.internalAssignableRoles());
	}

	public Set<MembershipRole> allowedRoles() {
		return allowedRoles;
	}

	public boolean containsAll(Set<MembershipRole> roles) {
		return roles != null && !roles.isEmpty() && roles.stream().allMatch(allowedRoles::contains);
	}

	public void requireAssignable(Set<MembershipRole> roles) {
		if (!containsAll(roles)) {
			throw new AccessPolicyViolation("Requested roles are outside the assignable role envelope");
		}
	}
}
