package com.nexa.api.tenantmanagement.presentation.rest;

import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.TenantConfigurationModels;
import com.nexa.api.tenantmanagement.application.port.in.TenantConfigurationUseCase;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionPolicy;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.nexa.api.tenantmanagement.presentation.rest.OrganizationAdministrationController.PreconditionRequiredException;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
@Tag(name = "Tenant Configuration")
@SecurityRequirement(name = "bearerAuth")
public final class TenantConfigurationController {
	private final TenantConfigurationUseCase configuration;

	public TenantConfigurationController(TenantConfigurationUseCase configuration) { this.configuration = configuration; }

	@GetMapping("/organization")
	public ResponseEntity<TenantConfigurationModels.OrganizationProfileView> organization(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) {
		var value = configuration.organizationProfile(context);
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@PatchMapping("/organization")
	public ResponseEntity<TenantConfigurationModels.OrganizationProfileView> updateOrganization(
			@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestBody OrganizationProfileRequest request, HttpServletRequest servletRequest) {
		var value = configuration.updateOrganizationProfile(context, request.toView(), version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@GetMapping("/workspaces/{workspaceId}/settings")
	public ResponseEntity<TenantConfigurationModels.WorkspaceSettingsView> workspaceSettings(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context, @PathVariable String workspaceId) {
		var value = configuration.workspaceSettings(context, workspaceId);
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@PatchMapping("/workspaces/{workspaceId}/settings")
	public ResponseEntity<TenantConfigurationModels.WorkspaceSettingsView> updateWorkspaceSettings(
			@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context, @PathVariable String workspaceId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody WorkspaceSettingsRequest request,
			HttpServletRequest servletRequest) {
		var current = configuration.workspaceSettings(context, workspaceId);
		var value = configuration.updateWorkspaceSettings(context, workspaceId, request.toView(current.workspaceId(), current.version()), version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@GetMapping("/settings/regional")
	public ResponseEntity<TenantConfigurationModels.RegionalSettingsView> regional(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) { var value = configuration.regionalSettings(context); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

	@PatchMapping("/settings/regional")
	public ResponseEntity<TenantConfigurationModels.RegionalSettingsView> updateRegional(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody RegionalSettingsRequest request, HttpServletRequest servletRequest) {
		var value = configuration.updateRegionalSettings(context, request.toView(0), version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@GetMapping("/settings/units")
	public ResponseEntity<TenantConfigurationModels.UnitPreferencesView> units(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) { var value = configuration.unitPreferences(context); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

	@PatchMapping("/settings/units")
	public ResponseEntity<TenantConfigurationModels.UnitPreferencesView> updateUnits(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody UnitPreferencesRequest request, HttpServletRequest servletRequest) {
		var value = configuration.updateUnitPreferences(context, request.toView(0), version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@GetMapping("/workspaces/{workspaceId}/operational-settings")
	public ResponseEntity<TenantConfigurationModels.OperationalSettingsView> operational(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context, @PathVariable String workspaceId) { var value = configuration.operationalSettings(context, workspaceId); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

	@PatchMapping("/workspaces/{workspaceId}/operational-settings")
	public ResponseEntity<TenantConfigurationModels.OperationalSettingsView> updateOperational(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@PathVariable String workspaceId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestBody OperationalSettingsRequest request, HttpServletRequest servletRequest) {
		var current = configuration.operationalSettings(context, workspaceId);
		var value = configuration.updateOperationalSettings(context, workspaceId, request.toView(current.workspaceId(), current.version()), version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@GetMapping("/workspaces/{workspaceId}/notifications")
	public ResponseEntity<TenantConfigurationModels.NotificationSettingsView> notifications(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context, @PathVariable String workspaceId) { var value = configuration.notificationSettings(context, workspaceId); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

	@PatchMapping("/workspaces/{workspaceId}/notifications")
	public ResponseEntity<TenantConfigurationModels.NotificationSettingsView> updateNotifications(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@PathVariable String workspaceId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestBody TenantConfigurationModels.NotificationSettingsView request, HttpServletRequest servletRequest) {
		var value = configuration.updateNotificationSettings(context, workspaceId, request, version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@GetMapping("/settings/security")
	public ResponseEntity<TenantConfigurationModels.TenantSecuritySettingsView> security(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) { var value = configuration.tenantSecuritySettings(context); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

	@PatchMapping("/settings/security")
	public ResponseEntity<TenantConfigurationModels.TenantSecuritySettingsView> updateSecurity(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody SecuritySettingsRequest request, HttpServletRequest servletRequest) {
		var value = configuration.updateTenantSecuritySettings(context, request.toView(0), version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@GetMapping("/custom-field-definitions")
	public List<TenantConfigurationModels.CustomFieldView> customFields(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@RequestParam(required = false) String scope, @RequestParam(defaultValue = "false") boolean includeInactive) { return configuration.customFields(context, scope, includeInactive); }

	@PostMapping("/custom-field-definitions")
	public ResponseEntity<TenantConfigurationModels.CustomFieldView> createCustomField(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@RequestBody CustomFieldRequest request, HttpServletRequest servletRequest) {
		var value = configuration.createCustomField(context, request.toView(null, 0), correlation(servletRequest));
		return ResponseEntity.status(201).eTag(etag(value.version())).body(value);
	}

	@PatchMapping("/custom-field-definitions/{id}")
	public ResponseEntity<TenantConfigurationModels.CustomFieldView> updateCustomField(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody CustomFieldRequest request, HttpServletRequest servletRequest) {
		var value = configuration.updateCustomField(context, id, request.toView(id, 0), version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@PostMapping("/custom-field-definitions/{id}/activations")
	public ResponseEntity<TenantConfigurationModels.CustomFieldView> activateCustomField(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch, HttpServletRequest servletRequest) {
		var value = configuration.setCustomFieldActive(context, id, true, version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@PostMapping("/custom-field-definitions/{id}/deactivations")
	public ResponseEntity<TenantConfigurationModels.CustomFieldView> deactivateCustomField(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context,
			@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch, HttpServletRequest servletRequest) {
		var value = configuration.setCustomFieldActive(context, id, false, version(ifMatch), correlation(servletRequest));
		return ResponseEntity.ok().eTag(etag(value.version())).body(value);
	}

	@GetMapping("/access-matrix")
	public List<TenantConfigurationModels.AccessMatrixEntry> accessMatrix(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) {
		context.requirePermission(Permission.TENANT_READ);
		return Arrays.stream(MembershipRole.values()).map(role -> new TenantConfigurationModels.AccessMatrixEntry(role.name(), PermissionPolicy.permissionsFor(role).stream().map(Permission::code).sorted().collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)))).toList();
	}

	@GetMapping("/plan-usage")
	public TenantConfigurationModels.PlanUsageView planUsage(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) { return configuration.planUsage(context); }

	@GetMapping("/plan-comparison")
	public List<TenantConfigurationModels.PlanOptionView> planComparison(@RequestAttribute("com.nexa.api.tenantmanagement.application.model.CurrentAccessContext") CurrentAccessContext context) {
		context.requirePermission(Permission.TENANT_READ);
		return List.of(
				new TenantConfigurationModels.PlanOptionView("STARTER", BigDecimal.ZERO, 5, 1, 250, false),
				new TenantConfigurationModels.PlanOptionView("STANDARD", BigDecimal.ZERO, 10, 3, 1000, true),
				new TenantConfigurationModels.PlanOptionView("PROFESSIONAL", BigDecimal.ZERO, 50, 10, 10000, false),
				new TenantConfigurationModels.PlanOptionView("ENTERPRISE", BigDecimal.ZERO, 250, 50, 100000, false));
	}

	private static long version(String value) {
		if (value == null || value.isBlank()) throw new PreconditionRequiredException();
		try { return Long.parseLong(value.replace("\"", "").trim()); } catch (NumberFormatException exception) { throw new PreconditionRequiredException(); }
	}
	private static String etag(long version) { return "\"" + version + "\""; }
	private static String correlation(HttpServletRequest request) { Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME); return value == null ? "unknown" : value.toString(); }

	public record OrganizationProfileRequest(String legalName, String displayName, String businessIdentifier, String operationCategory) {
		TenantConfigurationModels.OrganizationProfileView toView() { return new TenantConfigurationModels.OrganizationProfileView(legalName, displayName, businessIdentifier, operationCategory, 0); }
	}
	public record WorkspaceSettingsRequest(String defaultWorkspaceBehavior, String warehousePreferenceStrategy) {
		TenantConfigurationModels.WorkspaceSettingsView toView(String id, long version) { return new TenantConfigurationModels.WorkspaceSettingsView(id, defaultWorkspaceBehavior, warehousePreferenceStrategy, version); }
	}
	public record RegionalSettingsRequest(String timezone, String language, String currency, String countryRegion, String dateTimePolicy, String locale) {
		TenantConfigurationModels.RegionalSettingsView toView(long version) { return new TenantConfigurationModels.RegionalSettingsView(timezone, language, currency, countryRegion, dateTimePolicy, locale, version); }
	}
	public record UnitPreferencesRequest(String massUnit, String temperatureUnit, String distanceUnit, String volumeUnit) {
		TenantConfigurationModels.UnitPreferencesView toView(long version) { return new TenantConfigurationModels.UnitPreferencesView(massUnit, temperatureUnit, distanceUnit, volumeUnit, version); }
	}
	public record OperationalSettingsRequest(String defaultWarehouseSelectionPolicy, String orderCutoffPolicy, String fulfillmentDefaults, String inventoryVisibilityPolicy, String buyerAvailabilityPolicy, java.time.LocalTime operatingHoursStart, java.time.LocalTime operatingHoursEnd, int orderCutoffMinutes, boolean thermalLogRequired) {
		TenantConfigurationModels.OperationalSettingsView toView(String id, long version) { return new TenantConfigurationModels.OperationalSettingsView(id, defaultWarehouseSelectionPolicy, orderCutoffPolicy, fulfillmentDefaults, inventoryVisibilityPolicy, buyerAvailabilityPolicy, operatingHoursStart, operatingHoursEnd, orderCutoffMinutes, thermalLogRequired, version); }
	}
	public record SecuritySettingsRequest(int passwordMinLength, int sessionDurationMinutes, int invitationExpirationHours, String requiredEmailDomain) {
		TenantConfigurationModels.TenantSecuritySettingsView toView(long version) { return new TenantConfigurationModels.TenantSecuritySettingsView(passwordMinLength, sessionDurationMinutes, invitationExpirationHours, requiredEmailDomain, version); }
	}
	public record CustomFieldRequest(String fieldKey, String label, String fieldKind, String scope, boolean required, boolean uniqueValue, int displayOrder, boolean active) {
		TenantConfigurationModels.CustomFieldView toView(UUID id, long version) { return new TenantConfigurationModels.CustomFieldView(id, fieldKey, label, fieldKind, scope, required, uniqueValue, displayOrder, active, version); }
	}
}
