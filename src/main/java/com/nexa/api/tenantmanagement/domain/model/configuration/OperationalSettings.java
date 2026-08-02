package com.nexa.api.tenantmanagement.domain.model.configuration;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;

import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record OperationalSettings(String defaultWarehouseSelectionPolicy, String orderCutoffPolicy,
		String fulfillmentDefaults, String inventoryVisibilityPolicy, String buyerAvailabilityPolicy,
		LocalTime operatingHoursStart, LocalTime operatingHoursEnd, int orderCutoffMinutes,
		boolean thermalLogRequired, long version) {
	public OperationalSettings {
		defaultWarehouseSelectionPolicy = normalized(defaultWarehouseSelectionPolicy, Set.of("MANUAL", "PREFERRED"), "warehouse selection policy");
		orderCutoffPolicy = normalized(orderCutoffPolicy, Set.of("WORKSPACE_HOURS", "FIXED_TIME"), "order cutoff policy");
		fulfillmentDefaults = normalized(fulfillmentDefaults, Set.of("STANDARD", "FEFO"), "fulfillment defaults");
		inventoryVisibilityPolicy = normalized(inventoryVisibilityPolicy, Set.of("COARSE", "DETAILED"), "inventory visibility policy");
		buyerAvailabilityPolicy = normalized(buyerAvailabilityPolicy, Set.of("AVAILABLE_ONLY", "ALL_ACTIVE"), "buyer availability policy");
		operatingHoursStart = Objects.requireNonNull(operatingHoursStart, "Operating start is required");
		operatingHoursEnd = Objects.requireNonNull(operatingHoursEnd, "Operating end is required");
		if (!operatingHoursEnd.isAfter(operatingHoursStart)) throw new TenantManagementInvariantViolation("Operating hours are invalid");
		if (orderCutoffMinutes < 0 || orderCutoffMinutes > 1440) throw new TenantManagementInvariantViolation("Order cutoff is invalid");
	}

	private static String normalized(String value, Set<String> allowed, String label) {
		String normalized = Objects.requireNonNullElse(value, "").strip().toUpperCase(Locale.ROOT);
		if (!allowed.contains(normalized)) throw new TenantManagementInvariantViolation("Unsupported " + label);
		return normalized;
	}
}
