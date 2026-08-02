package com.nexa.api.tenantmanagement.application.port.in;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.TenantConfigurationModels;

import java.util.List;
import java.util.UUID;

public interface TenantConfigurationUseCase {
	TenantConfigurationModels.OrganizationProfileView organizationProfile(CurrentAccessContext context);
	TenantConfigurationModels.OrganizationProfileView updateOrganizationProfile(CurrentAccessContext context,
			TenantConfigurationModels.OrganizationProfileView request, long expectedVersion, String correlationId);
	TenantConfigurationModels.WorkspaceSettingsView workspaceSettings(CurrentAccessContext context, String workspaceId);
	TenantConfigurationModels.WorkspaceSettingsView updateWorkspaceSettings(CurrentAccessContext context, String workspaceId,
			TenantConfigurationModels.WorkspaceSettingsView request, long expectedVersion, String correlationId);
	TenantConfigurationModels.RegionalSettingsView regionalSettings(CurrentAccessContext context);
	TenantConfigurationModels.RegionalSettingsView updateRegionalSettings(CurrentAccessContext context,
			TenantConfigurationModels.RegionalSettingsView request, long expectedVersion, String correlationId);
	TenantConfigurationModels.UnitPreferencesView unitPreferences(CurrentAccessContext context);
	TenantConfigurationModels.UnitPreferencesView updateUnitPreferences(CurrentAccessContext context,
			TenantConfigurationModels.UnitPreferencesView request, long expectedVersion, String correlationId);
	TenantConfigurationModels.OperationalSettingsView operationalSettings(CurrentAccessContext context, String workspaceId);
	TenantConfigurationModels.OperationalSettingsView updateOperationalSettings(CurrentAccessContext context, String workspaceId,
			TenantConfigurationModels.OperationalSettingsView request, long expectedVersion, String correlationId);
	TenantConfigurationModels.NotificationSettingsView notificationSettings(CurrentAccessContext context, String workspaceId);
	TenantConfigurationModels.NotificationSettingsView updateNotificationSettings(CurrentAccessContext context, String workspaceId,
			TenantConfigurationModels.NotificationSettingsView request, long expectedVersion, String correlationId);
	TenantConfigurationModels.TenantSecuritySettingsView tenantSecuritySettings(CurrentAccessContext context);
	TenantConfigurationModels.TenantSecuritySettingsView updateTenantSecuritySettings(CurrentAccessContext context,
			TenantConfigurationModels.TenantSecuritySettingsView request, long expectedVersion, String correlationId);
	List<TenantConfigurationModels.CustomFieldView> customFields(CurrentAccessContext context, String scope, boolean includeInactive);
	TenantConfigurationModels.CustomFieldView createCustomField(CurrentAccessContext context,
			TenantConfigurationModels.CustomFieldView request, String correlationId);
	TenantConfigurationModels.CustomFieldView updateCustomField(CurrentAccessContext context, UUID id,
			TenantConfigurationModels.CustomFieldView request, long expectedVersion, String correlationId);
	TenantConfigurationModels.CustomFieldView setCustomFieldActive(CurrentAccessContext context, UUID id,
			boolean active, long expectedVersion, String correlationId);
	TenantConfigurationModels.PlanUsageView planUsage(CurrentAccessContext context);
}
