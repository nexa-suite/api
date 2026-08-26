package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.TenantConfigurationModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.CustomFieldDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.NotificationPreference;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.OperationalSettings;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.OrganizationProfile;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.ReferencePlanAssignment;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.RegionalSettings;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.TenantSecuritySettings;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.UnitPreferences;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantConfigurationPort {
	Optional<OrganizationProfile> findOrganizationProfile(String tenantId);
	int updateOrganizationProfile(String tenantId, OrganizationProfile profile);
	Optional<RegionalSettings> findRegionalSettings(String tenantId);
	int updateRegionalSettings(String tenantId, RegionalSettings settings);
	Optional<UnitPreferences> findUnitPreferences(String tenantId);
	int updateUnitPreferences(String tenantId, UnitPreferences preferences);
	Optional<OperationalSettings> findOperationalSettings(String workspaceId);
	int updateOperationalSettings(String workspaceId, OperationalSettings settings);
	Optional<TenantConfigurationModels.WorkspaceSettingsView> findWorkspaceSettings(String workspaceId);
	int updateWorkspaceSettings(String workspaceId, String defaultBehavior, String warehouseStrategy, long expectedVersion);
	List<NotificationPreference> findNotificationPreferences(String workspaceId);
	long notificationVersion(String workspaceId);
	int updateNotificationPreference(String workspaceId, NotificationPreference preference);
	Optional<TenantSecuritySettings> findTenantSecuritySettings(String tenantId);
	int updateTenantSecuritySettings(String tenantId, TenantSecuritySettings settings);
	List<TenantConfigurationModels.CustomFieldView> findCustomFields(String tenantId, String workspaceId, String scope, boolean includeInactive);
	Optional<TenantConfigurationModels.CustomFieldView> findCustomField(String tenantId, String workspaceId, UUID id);
	int createCustomField(String tenantId, String workspaceId, CustomFieldDefinition definition);
	int updateCustomField(String tenantId, String workspaceId, UUID id, CustomFieldDefinition definition, long expectedVersion);
	int updateCustomFieldActive(String tenantId, String workspaceId, UUID id, boolean active, long expectedVersion);
	Optional<ReferencePlanAssignment> findReferencePlan(String tenantId);
	int updateReferencePlan(String tenantId, ReferencePlanAssignment plan, long expectedVersion);
	TenantConfigurationModels.PlanUsageView planUsage(String tenantId, String workspaceId);
}
