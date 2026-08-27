package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleCatalog;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;

import java.util.Locale;

/**
 * The role is owned by a workspace membership. IAM may authenticate a user, but it does not define this role.
 */
public enum MembershipRole {
	TENANT_ADMIN,
	COMPANY_OWNER,
	SALES,
	WAREHOUSE,
	LOGISTICS,
	BUYER;

	public RoleDefinition definition() {
		return RoleCatalog.definitionFor(this);
	}

	public boolean isInternalAssignable() {
		return RoleCatalog.internalAssignableRoles().contains(this);
	}

	public static MembershipRole from(String value) {
		if (value == null || value.isBlank()) {
			throw new TenantManagementInvariantViolation("Membership role is required");
		}
		try {
			return value.strip().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT)
					.transform(MembershipRole::valueOf);
		} catch (IllegalArgumentException exception) {
			throw new TenantManagementInvariantViolation("Unknown membership role");
		}
	}
}
