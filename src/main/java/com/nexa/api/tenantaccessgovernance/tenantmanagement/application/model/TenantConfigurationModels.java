package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public final class TenantConfigurationModels {
	private TenantConfigurationModels() { }

	public record OrganizationProfileView(String legalName, String displayName,
			@Schema(description = "Legacy free-form identifier retained for existing clients; it is not interpreted as a RUC.")
			String businessIdentifier,
			String operationCategory, long version,
			@Schema(description = "Peruvian RUC syntax: exactly 11 digits. Format only; no legal or tax registry verification is performed.", example = "20123456789")
			String ruc,
			@Schema(description = "Structured company legal address. Null when not configured.")
			OrganizationLegalAddressView legalAddress) {
		public OrganizationProfileView(String legalName, String displayName, String businessIdentifier,
				String operationCategory, long version) {
			this(legalName, displayName, businessIdentifier, operationCategory, version, null, null);
		}
	}

	@Schema(description = "Structured Tenant company legal address. Peru geography codes are checked for format and hierarchy only; no legal or registry verification is performed.")
	public record OrganizationLegalAddressView(
			@Schema(description = "Two-letter country code.", example = "PE") String countryCode,
			@Schema(description = "Primary address line.", example = "Av. Ejemplo 123") String addressLine,
			@Schema(description = "Optional two-digit Peru department code.", example = "15") String departmentCode,
			@Schema(description = "Optional four-digit Peru province code beginning with departmentCode.", example = "1501") String provinceCode,
			@Schema(description = "Optional six-digit Peru district code beginning with provinceCode.", example = "150101") String districtCode,
			String roadType, String streetName, String streetNumber, String interior, String postalCode, String reference) { }

	public record OrganizationProfileUpdate(String legalName, String displayName, String businessIdentifier,
			String operationCategory, boolean rucProvided, String ruc, boolean legalAddressProvided,
			OrganizationLegalAddressView legalAddress) { }

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

	public record AccessMatrixEntry(
			@Schema(description = "Built-in membership role. This list does not include tenant-defined role definitions.")
			String role,
			@Schema(deprecated = true, description = "Legacy permission codes retained for compatibility. They may differ from typed permission keys and must not be used as an effective-access decision.")
			Set<String> permissions,
			@Schema(description = "Canonical PermissionKey codes declared by the built-in role. Informational template only; effective access also depends on membership, surface, tenant/workspace, object relationship, grants, and legacy guards.")
			Set<String> permissionKeys) {
		public AccessMatrixEntry {
			permissions = Set.copyOf(permissions);
			permissionKeys = Set.copyOf(permissionKeys);
		}
	}
}
