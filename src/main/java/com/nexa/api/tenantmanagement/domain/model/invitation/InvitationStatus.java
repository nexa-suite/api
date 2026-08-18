package com.nexa.api.tenantmanagement.domain.model.invitation;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.util.Locale;

public enum InvitationStatus {
	PENDING, ACCEPTED, REVOKED, EXPIRED;

	public static InvitationStatus from(String value) {
		try { return valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException exception) { throw new TenantManagementInvariantViolation("Invitation status is invalid"); }
	}
}
