package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TenantConfigurationModels {
	private TenantConfigurationModels() { }

	public record OrganizationProfileView(String legalName, String displayName, String businessIdentifier,
			String operationCategory, long version) { }

	public record WorkspaceSettingsView(String workspaceId, String defaultWorkspaceBehavior,
			String warehousePreferenceStrategy, long version) { }

	public record RegionalSettingsView(String timezone, String language, String currency, String countryRegion,
			String dateTimePolicy, String locale, long version) { }

	public record UnitPreferencesView(String massUnit, String temperatureUnit, String distanceUnit,
			String volumeUnit, long version) { }

	public record OperationalSettingsView(String workspaceId, String defaultWarehouseSelectionPolicy,
			String orderCutoffPolicy, String fulfillmentDefaults, String inventoryVisibilityPolicy,
			String buyerAvailabilityPolicy, LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
			int orderCutoffMinutes, boolean thermalLogRequired, long version) { }

	public record NotificationPreferenceView(String eventCategory, String channel, boolean enabled, long version) { }

	public record NotificationSettingsView(List<NotificationPreferenceView> preferences, long version) {
		public NotificationSettingsView { preferences = List.copyOf(preferences); }
	}

	public record TenantSecuritySettingsView(int passwordMinLength, int sessionDurationMinutes,
			int invitationExpirationHours, String requiredEmailDomain, long version) { }

	public record CustomFieldView(UUID id, String fieldKey, String label, String fieldKind, String scope,
			boolean required, boolean uniqueValue, int displayOrder, boolean active, long version) { }

	public record PlanUsageView(String planCode, BigDecimal monthlyPrice, int seatLimit, int workspaceLimit,
			int transactionLimit, int activeUsers, int workspaceCount, long transactionCount, long version) { }

	public record PlanOptionView(String planCode, BigDecimal monthlyPrice, int seatLimit, int workspaceLimit,
			int transactionLimit, boolean current) { }

	public record AccessMatrixEntry(String role, Set<String> permissions) {
		public AccessMatrixEntry { permissions = Set.copyOf(permissions); }
	}
}
