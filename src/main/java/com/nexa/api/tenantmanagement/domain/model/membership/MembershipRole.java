package com.nexa.api.tenantmanagement.domain.model.membership;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

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
