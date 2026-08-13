package com.nexa.api.tenantmanagement.application.service;

import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.TenantConfigurationModels;
import com.nexa.api.tenantmanagement.application.port.in.TenantConfigurationUseCase;
import com.nexa.api.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantmanagement.application.port.out.TenantConfigurationPort;
import com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.ConcurrencyConflictException;
import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.configuration.CustomFieldDefinition;
import com.nexa.api.tenantmanagement.domain.model.configuration.NotificationPreference;
import com.nexa.api.tenantmanagement.domain.model.configuration.OperationalSettings;
import com.nexa.api.tenantmanagement.domain.model.configuration.OrganizationProfile;
import com.nexa.api.tenantmanagement.domain.model.configuration.RegionalSettings;
import com.nexa.api.tenantmanagement.domain.model.configuration.TenantSecuritySettings;
import com.nexa.api.tenantmanagement.domain.model.configuration.UnitPreferences;

import java.time.Clock;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class TenantConfigurationService implements TenantConfigurationUseCase {
	private final TenantConfigurationPort port;
	private final OrganizationAdministrationPort scope;
	private final SecurityAuditPort audit;
	private final Clock clock;

	public TenantConfigurationService(TenantConfigurationPort port, OrganizationAdministrationPort scope,
			SecurityAuditPort audit, Clock clock) {
		this.port = Objects.requireNonNull(port);
		this.scope = Objects.requireNonNull(scope);
		this.audit = Objects.requireNonNull(audit);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public TenantConfigurationModels.OrganizationProfileView organizationProfile(CurrentAccessContext context) {
		read(context);
		return view(port.findOrganizationProfile(context.tenantId().toString()).orElseThrow(() -> new ApiResourceNotFoundException("organization settings")));
	}

	@Override
	public TenantConfigurationModels.OrganizationProfileView updateOrganizationProfile(CurrentAccessContext context,
			TenantConfigurationModels.OrganizationProfileView request, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_ORGANIZATION_MANAGE);
		OrganizationProfile current = port.findOrganizationProfile(context.tenantId().toString())
				.orElseThrow(() -> new ApiResourceNotFoundException("organization settings"));
		OrganizationProfile profile = new OrganizationProfile(request.legalName(), request.displayName(), request.businessIdentifier(), request.operationCategory(), expectedVersion);
		if (port.updateOrganizationProfile(context.tenantId().toString(), profile) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "ORGANIZATION_UPDATED", correlationId, organizationChangeMetadata(current, profile));
		return view(new OrganizationProfile(profile.legalName(), profile.displayName(), profile.businessIdentifier(), profile.operationCategory(), expectedVersion + 1));
	}

	@Override
	public TenantConfigurationModels.WorkspaceSettingsView workspaceSettings(CurrentAccessContext context, String workspaceId) {
		read(context);
		return port.findWorkspaceSettings(requireWorkspace(context, workspaceId)).orElseThrow(() -> new ApiResourceNotFoundException("workspace settings"));
	}

	@Override
	public TenantConfigurationModels.WorkspaceSettingsView updateWorkspaceSettings(CurrentAccessContext context, String workspaceId,
			TenantConfigurationModels.WorkspaceSettingsView request, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		String scopedWorkspace = requireWorkspace(context, workspaceId);
		if (port.updateWorkspaceSettings(scopedWorkspace, request.defaultWorkspaceBehavior(), request.warehousePreferenceStrategy(), expectedVersion) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "OPERATIONAL_SETTINGS_CHANGED", correlationId, Map.of("section", "workspace-defaults"));
		return new TenantConfigurationModels.WorkspaceSettingsView(scopedWorkspace, request.defaultWorkspaceBehavior(), request.warehousePreferenceStrategy(), expectedVersion + 1);
	}

	@Override
	public TenantConfigurationModels.RegionalSettingsView regionalSettings(CurrentAccessContext context) {
		read(context);
		return view(port.findRegionalSettings(context.tenantId().toString()).orElseThrow(() -> new ApiResourceNotFoundException("regional settings")));
	}

	@Override
	public TenantConfigurationModels.RegionalSettingsView updateRegionalSettings(CurrentAccessContext context,
			TenantConfigurationModels.RegionalSettingsView request, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_ORGANIZATION_MANAGE);
		RegionalSettings settings = new RegionalSettings(request.timezone(), request.language(), request.currency(), request.countryRegion(), request.dateTimePolicy(), request.locale(), expectedVersion);
		if (port.updateRegionalSettings(context.tenantId().toString(), settings) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "REGIONAL_SETTINGS_CHANGED", correlationId, Map.of("section", "regional"));
		return view(new RegionalSettings(settings.timezone(), settings.language(), settings.currency(), settings.countryRegion(), settings.dateTimePolicy(), settings.locale(), expectedVersion + 1));
	}

	@Override
	public TenantConfigurationModels.UnitPreferencesView unitPreferences(CurrentAccessContext context) {
		read(context);
		return view(port.findUnitPreferences(context.tenantId().toString()).orElseThrow(() -> new ApiResourceNotFoundException("unit preferences")));
	}

	@Override
	public TenantConfigurationModels.UnitPreferencesView updateUnitPreferences(CurrentAccessContext context,
			TenantConfigurationModels.UnitPreferencesView request, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_ORGANIZATION_MANAGE);
		UnitPreferences preferences = new UnitPreferences(request.massUnit(), request.temperatureUnit(), request.distanceUnit(), request.volumeUnit(), expectedVersion);
		if (port.updateUnitPreferences(context.tenantId().toString(), preferences) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "UNIT_PREFERENCES_CHANGED", correlationId, Map.of("section", "units"));
		return view(new UnitPreferences(preferences.massUnit(), preferences.temperatureUnit(), preferences.distanceUnit(), preferences.volumeUnit(), expectedVersion + 1));
	}

	@Override
	public TenantConfigurationModels.OperationalSettingsView operationalSettings(CurrentAccessContext context, String workspaceId) {
		read(context);
		String scopedWorkspace = requireWorkspace(context, workspaceId);
		return view(scopedWorkspace, port.findOperationalSettings(scopedWorkspace).orElseThrow(() -> new ApiResourceNotFoundException("operational settings")));
	}

	@Override
	public TenantConfigurationModels.OperationalSettingsView updateOperationalSettings(CurrentAccessContext context, String workspaceId,
			TenantConfigurationModels.OperationalSettingsView request, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		String scopedWorkspace = requireWorkspace(context, workspaceId);
		OperationalSettings settings = new OperationalSettings(request.defaultWarehouseSelectionPolicy(), request.orderCutoffPolicy(), request.fulfillmentDefaults(), request.inventoryVisibilityPolicy(), request.buyerAvailabilityPolicy(), request.operatingHoursStart(), request.operatingHoursEnd(), request.orderCutoffMinutes(), request.thermalLogRequired(), expectedVersion);
		if (port.updateOperationalSettings(scopedWorkspace, settings) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "OPERATIONAL_SETTINGS_CHANGED", correlationId, Map.of("section", "operational"));
		return view(scopedWorkspace, new OperationalSettings(settings.defaultWarehouseSelectionPolicy(), settings.orderCutoffPolicy(), settings.fulfillmentDefaults(), settings.inventoryVisibilityPolicy(), settings.buyerAvailabilityPolicy(), settings.operatingHoursStart(), settings.operatingHoursEnd(), settings.orderCutoffMinutes(), settings.thermalLogRequired(), expectedVersion + 1));
	}

	@Override
	public TenantConfigurationModels.NotificationSettingsView notificationSettings(CurrentAccessContext context, String workspaceId) {
		read(context);
		String scopedWorkspace = requireWorkspace(context, workspaceId);
		List<TenantConfigurationModels.NotificationPreferenceView> values = port.findNotificationPreferences(scopedWorkspace).stream()
				.map(value -> new TenantConfigurationModels.NotificationPreferenceView(value.eventCategory(), value.channel(), value.enabled(), value.version())).toList();
		return new TenantConfigurationModels.NotificationSettingsView(values, port.notificationVersion(scopedWorkspace));
	}

	@Override
	public TenantConfigurationModels.NotificationSettingsView updateNotificationSettings(CurrentAccessContext context, String workspaceId,
			TenantConfigurationModels.NotificationSettingsView request, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.NOTIFICATION_MANAGE_PREFERENCES);
		String scopedWorkspace = requireWorkspace(context, workspaceId);
		if (port.notificationVersion(scopedWorkspace) != expectedVersion) throw new ConcurrencyConflictException();
		for (TenantConfigurationModels.NotificationPreferenceView value : request.preferences()) {
			if (port.updateNotificationPreference(scopedWorkspace, new NotificationPreference(value.eventCategory(), value.channel(), value.enabled(), value.version())) == 0) {
				throw new ConcurrencyConflictException();
			}
		}
		appendAudit(context, "NOTIFICATION_SETTINGS_CHANGED", correlationId, Map.of("section", "notifications"));
		List<TenantConfigurationModels.NotificationPreferenceView> updated = request.preferences().stream()
				.map(value -> new TenantConfigurationModels.NotificationPreferenceView(value.eventCategory(), value.channel(), value.enabled(), value.version() + 1))
				.toList();
		return new TenantConfigurationModels.NotificationSettingsView(updated, expectedVersion + 1);
	}

	@Override
	public TenantConfigurationModels.TenantSecuritySettingsView tenantSecuritySettings(CurrentAccessContext context) {
		read(context);
		return view(port.findTenantSecuritySettings(context.tenantId().toString()).orElseThrow(() -> new ApiResourceNotFoundException("tenant security settings")));
	}

	@Override
	public TenantConfigurationModels.TenantSecuritySettingsView updateTenantSecuritySettings(CurrentAccessContext context,
			TenantConfigurationModels.TenantSecuritySettingsView request, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_SECURITY_MANAGE);
		TenantSecuritySettings settings = new TenantSecuritySettings(request.passwordMinLength(), request.sessionDurationMinutes(), request.invitationExpirationHours(), request.requiredEmailDomain(), expectedVersion);
		if (port.updateTenantSecuritySettings(context.tenantId().toString(), settings) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "TENANT_SECURITY_SETTINGS_CHANGED", correlationId, Map.of("section", "security"));
		return view(new TenantSecuritySettings(settings.passwordMinLength(), settings.sessionDurationMinutes(), settings.invitationExpirationHours(), settings.requiredEmailDomain(), expectedVersion + 1));
	}

	@Override
	public List<TenantConfigurationModels.CustomFieldView> customFields(CurrentAccessContext context, String scopeName, boolean includeInactive) {
		read(context);
		return port.findCustomFields(context.tenantId().toString(), context.workspaceId().toString(), scopeName, includeInactive);
	}

	@Override
	public TenantConfigurationModels.CustomFieldView createCustomField(CurrentAccessContext context,
			TenantConfigurationModels.CustomFieldView request, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		UUID id = request.id() == null ? UUID.randomUUID() : request.id();
		CustomFieldDefinition definition = definition(id, request, 0);
		if (port.createCustomField(context.tenantId().toString(), context.workspaceId().toString(), definition) == 0) throw new CustomFieldConflictException();
		appendAudit(context, "CUSTOM_FIELD_DEFINITION_CREATED", correlationId, Map.of("fieldKey", definition.fieldKey(), "scope", definition.scope()));
		return port.findCustomField(context.tenantId().toString(), context.workspaceId().toString(), id).orElseThrow(() -> new ApiResourceNotFoundException("custom field"));
	}

	@Override
	public TenantConfigurationModels.CustomFieldView updateCustomField(CurrentAccessContext context, UUID id,
			TenantConfigurationModels.CustomFieldView request, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		CustomFieldDefinition definition = definition(id, request, expectedVersion);
		if (port.updateCustomField(context.tenantId().toString(), context.workspaceId().toString(), id, definition, expectedVersion) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, "CUSTOM_FIELD_DEFINITION_UPDATED", correlationId, Map.of("fieldKey", definition.fieldKey(), "scope", definition.scope()));
		return port.findCustomField(context.tenantId().toString(), context.workspaceId().toString(), id).orElseThrow(() -> new ApiResourceNotFoundException("custom field"));
	}

	@Override
	public TenantConfigurationModels.CustomFieldView setCustomFieldActive(CurrentAccessContext context, UUID id,
			boolean active, long expectedVersion, String correlationId) {
		context.requirePermission(PermissionKey.TENANT_WORKSPACE_MANAGE);
		if (port.updateCustomFieldActive(context.tenantId().toString(), context.workspaceId().toString(), id, active, expectedVersion) == 0) throw new ConcurrencyConflictException();
		appendAudit(context, active ? "CUSTOM_FIELD_DEFINITION_UPDATED" : "CUSTOM_FIELD_DEFINITION_DISABLED", correlationId, Map.of("active", active));
		return port.findCustomField(context.tenantId().toString(), context.workspaceId().toString(), id).orElseThrow(() -> new ApiResourceNotFoundException("custom field"));
	}

	@Override
	public TenantConfigurationModels.PlanUsageView planUsage(CurrentAccessContext context) {
		read(context);
		return port.planUsage(context.tenantId().toString(), context.workspaceId().toString());
	}

	private String requireWorkspace(CurrentAccessContext context, String workspaceId) {
		try {
			String value = UUID.fromString(workspaceId).toString();
			return scope.findWorkspace(context.tenantId().toString(), value).map(workspace -> workspace.id()).orElseThrow(() -> new ApiResourceNotFoundException("workspace"));
		} catch (IllegalArgumentException exception) {
			throw new ApiResourceNotFoundException("workspace");
		}
	}

	private static CustomFieldDefinition definition(UUID id, TenantConfigurationModels.CustomFieldView request, long version) {
		return new CustomFieldDefinition(id, request.fieldKey(), request.label(), request.fieldKind(), request.scope(), request.required(), request.uniqueValue(), request.displayOrder(), request.active(), version);
	}

	private static TenantConfigurationModels.OrganizationProfileView view(OrganizationProfile value) { return new TenantConfigurationModels.OrganizationProfileView(value.legalName(), value.displayName(), value.businessIdentifier(), value.operationCategory(), value.version()); }
	private static TenantConfigurationModels.RegionalSettingsView view(RegionalSettings value) { return new TenantConfigurationModels.RegionalSettingsView(value.timezone(), value.language(), value.currency(), value.countryRegion(), value.dateTimePolicy(), value.locale(), value.version()); }
	private static TenantConfigurationModels.UnitPreferencesView view(UnitPreferences value) { return new TenantConfigurationModels.UnitPreferencesView(value.massUnit(), value.temperatureUnit(), value.distanceUnit(), value.volumeUnit(), value.version()); }
	private static TenantConfigurationModels.OperationalSettingsView view(String workspaceId, OperationalSettings value) { return new TenantConfigurationModels.OperationalSettingsView(workspaceId, value.defaultWarehouseSelectionPolicy(), value.orderCutoffPolicy(), value.fulfillmentDefaults(), value.inventoryVisibilityPolicy(), value.buyerAvailabilityPolicy(), value.operatingHoursStart(), value.operatingHoursEnd(), value.orderCutoffMinutes(), value.thermalLogRequired(), value.version()); }
	private static TenantConfigurationModels.TenantSecuritySettingsView view(TenantSecuritySettings value) { return new TenantConfigurationModels.TenantSecuritySettingsView(value.passwordMinLength(), value.sessionDurationMinutes(), value.invitationExpirationHours(), value.requiredEmailDomain(), value.version()); }
	private static Map<String, Object> organizationChangeMetadata(OrganizationProfile before, OrganizationProfile after) {
		Map<String, Object> oldValues = new LinkedHashMap<>();
		oldValues.put("legalName", before.legalName());
		oldValues.put("displayName", before.displayName());
		oldValues.put("businessIdentifier", before.businessIdentifier());
		oldValues.put("operationCategory", before.operationCategory());
		Map<String, Object> newValues = new LinkedHashMap<>();
		newValues.put("legalName", after.legalName());
		newValues.put("displayName", after.displayName());
		newValues.put("businessIdentifier", after.businessIdentifier());
		newValues.put("operationCategory", after.operationCategory());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("section", "organization");
		metadata.put("oldValues", oldValues);
		metadata.put("newValues", newValues);
		return metadata;
	}

	private static void read(CurrentAccessContext context) { context.requirePermission(Permission.TENANT_READ); }
	private void appendAudit(CurrentAccessContext context, String type, String correlationId, Map<String, Object> metadata) {
		audit.append(new SecurityAuditPort.Event(type, context.userId().value(), null, context.tenantId().value(), context.workspaceId().value(), context.surface().name(), valueOrUnknown(correlationId), "unknown", clock.instant(), metadata));
	}
	private static String valueOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : value; }

	public static final class CustomFieldConflictException extends RuntimeException { }
}
