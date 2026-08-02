package com.nexa.api.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

public record TenantSecuritySettings(int passwordMinLength, int sessionDurationMinutes,
		int invitationExpirationHours, String requiredEmailDomain, long version) {
	public TenantSecuritySettings {
		if (passwordMinLength < 12 || passwordMinLength > 128) throw new TenantManagementInvariantViolation("Password minimum cannot weaken platform policy");
		if (sessionDurationMinutes < 30 || sessionDurationMinutes > 1440) throw new TenantManagementInvariantViolation("Session duration is outside secure bounds");
		if (invitationExpirationHours < 1 || invitationExpirationHours > 168) throw new TenantManagementInvariantViolation("Invitation expiration is outside secure bounds");
		if (requiredEmailDomain != null) {
			requiredEmailDomain = requiredEmailDomain.strip().toLowerCase(java.util.Locale.ROOT);
			if (requiredEmailDomain.isBlank() || !requiredEmailDomain.matches("[a-z0-9.-]+")) throw new TenantManagementInvariantViolation("Email domain is invalid");
		}
	}
}
