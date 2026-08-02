package com.nexa.api.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

public record ReferencePlanAssignment(String planCode, BigDecimal monthlyPrice, int seatLimit,
		int workspaceLimit, int transactionLimit, long version) {
	public ReferencePlanAssignment {
		planCode = planCode == null ? "" : planCode.strip().toUpperCase(Locale.ROOT);
		if (!Set.of("STARTER", "STANDARD", "PROFESSIONAL", "ENTERPRISE").contains(planCode)) throw new TenantManagementInvariantViolation("Reference plan is invalid");
		if (monthlyPrice == null || monthlyPrice.signum() < 0 || seatLimit < 1 || workspaceLimit < 1 || transactionLimit < 1) throw new TenantManagementInvariantViolation("Reference plan limits are invalid");
	}
}
