package com.nexa.api.tenantaccessgovernance.tenantmanagement.application;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.TenantConfigurationModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.WorkspaceSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.TenantConfigurationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service.TenantConfigurationService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionCatalog;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.OperationalSettings;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalSettingsLeadTimeTests {
	private static final UUID TENANT = UUID.fromString("56f46cda-f137-4d8a-9ab7-7191005007a3");
	private static final UUID WORKSPACE = UUID.fromString("c61d6863-bc19-4190-a5c5-0fa990a0f583");
	private static final UUID USER = UUID.fromString("af97460b-1a4d-4e8c-abec-860d16696afd");

	@Test
	void operationalSettingsUseThreeDayCompatibilityDefaultAndRequireNonNegativeCalendarDays() {
		assertThat(settings(7).minimumDeliveryLeadDays()).isEqualTo(3);
		assertThat(settings(7, 0).minimumDeliveryLeadDays()).isZero();
		assertThat(settings(7, 30).minimumDeliveryLeadDays()).isEqualTo(30);
		assertThat(settings(7, Integer.MAX_VALUE).minimumDeliveryLeadDays()).isEqualTo(Integer.MAX_VALUE);
		assertThatThrownBy(() -> settings(7, -1)).isInstanceOf(TenantManagementInvariantViolation.class);
	}

	@Test
	void companyOwnerGetsOnlyNarrowDeliverySettingPermissionWhileTenantAdminKeepsBroadPermission() {
		Set<PermissionKey> owner = PermissionCatalog.forBuiltInRole(MembershipRole.COMPANY_OWNER);
		Set<PermissionKey> admin = PermissionCatalog.forBuiltInRole(MembershipRole.TENANT_ADMIN);

		assertThat(owner).contains(PermissionKey.TENANT_WORKSPACE_DELIVERY_LEAD_TIME_MANAGE)
				.doesNotContain(PermissionKey.TENANT_WORKSPACE_MANAGE);
		assertThat(admin).contains(PermissionKey.TENANT_WORKSPACE_DELIVERY_LEAD_TIME_MANAGE,
				PermissionKey.TENANT_WORKSPACE_MANAGE);
	}

	@Test
	void narrowPermissionCanChangeLeadDaysWithoutChangingOtherOperationalSettings() {
		TenantConfigurationPort port = mock(TenantConfigurationPort.class);
		OrganizationAdministrationPort scope = mock(OrganizationAdministrationPort.class);
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		OperationalSettings current = settings(7, 3);
		when(context.allows(PermissionKey.TENANT_WORKSPACE_MANAGE)).thenReturn(false);
		when(context.allows(PermissionKey.TENANT_WORKSPACE_DELIVERY_LEAD_TIME_MANAGE)).thenReturn(true);
		when(context.tenantId()).thenReturn(new TenantId(TENANT));
		when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE));
		when(context.userId()).thenReturn(new UserId(USER));
		when(context.surface()).thenReturn(Surface.PLATFORM);
		when(scope.findWorkspace(TENANT.toString(), WORKSPACE.toString())).thenReturn(Optional.of(
				new WorkspaceSummary(WORKSPACE.toString(), TENANT.toString(), "Main", "main", "ACTIVE", 0)));
		when(port.findOperationalSettings(WORKSPACE.toString())).thenReturn(Optional.of(current));
		when(port.updateOperationalSettings(any(), any())).thenReturn(1);

		var response = service(port, scope).updateOperationalSettings(context, WORKSPACE.toString(),
				view(8, 7), 7, "trace-lead-time");

		assertThat(response.minimumDeliveryLeadDays()).isEqualTo(8);
		assertThat(response.version()).isEqualTo(8);
		ArgumentCaptor<OperationalSettings> updated = ArgumentCaptor.forClass(OperationalSettings.class);
		verify(port).updateOperationalSettings(org.mockito.ArgumentMatchers.eq(WORKSPACE.toString()), updated.capture());
		assertThat(updated.getValue().defaultWarehouseSelectionPolicy()).isEqualTo("MANUAL");
		assertThat(updated.getValue().operatingHoursStart()).isEqualTo(LocalTime.of(8, 0));
		assertThat(updated.getValue().minimumDeliveryLeadDays()).isEqualTo(8);
	}

	@Test
	void narrowPermissionCannotChangeAnyOtherOperationalSetting() {
		TenantConfigurationPort port = mock(TenantConfigurationPort.class);
		OrganizationAdministrationPort scope = mock(OrganizationAdministrationPort.class);
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		when(context.allows(PermissionKey.TENANT_WORKSPACE_MANAGE)).thenReturn(false);
		when(context.allows(PermissionKey.TENANT_WORKSPACE_DELIVERY_LEAD_TIME_MANAGE)).thenReturn(true);
		when(context.tenantId()).thenReturn(new TenantId(TENANT));
		when(scope.findWorkspace(TENANT.toString(), WORKSPACE.toString())).thenReturn(Optional.of(
				new WorkspaceSummary(WORKSPACE.toString(), TENANT.toString(), "Main", "main", "ACTIVE", 0)));
		when(port.findOperationalSettings(WORKSPACE.toString())).thenReturn(Optional.of(settings(7, 3)));

		assertThatThrownBy(() -> service(port, scope).updateOperationalSettings(context, WORKSPACE.toString(),
				new TenantConfigurationModels.OperationalSettingsView(WORKSPACE.toString(), "PREFERRED", "WORKSPACE_HOURS",
						"STANDARD", "COARSE", "AVAILABLE_ONLY", LocalTime.of(8, 0), LocalTime.of(18, 0),
						120, false, 8, 7), 7, "trace-forbidden-change"))
				.isInstanceOf(AccessPolicyViolation.class);
		verify(port, never()).updateOperationalSettings(any(), any());
	}

	@Test
	void deliveryLeadTimeSourceRejectsInvalidVersionOrNegativeValue() {
		var tenant = new TenantId(TENANT);
		var workspace = new WorkspaceId(WORKSPACE);
		assertThat(new OperationalSettingsAccess.DeliveryLeadTimePolicySource(tenant, workspace,
				OperationalSettingsAccess.SourceState.PRESENT, 4L, 3).minimumDeliveryLeadDays()).isEqualTo(3);
		assertThatThrownBy(() -> new OperationalSettingsAccess.DeliveryLeadTimePolicySource(tenant, workspace,
				OperationalSettingsAccess.SourceState.PRESENT, -1L, 3)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperationalSettingsAccess.DeliveryLeadTimePolicySource(tenant, workspace,
				OperationalSettingsAccess.SourceState.PRESENT, 4L, -1)).isInstanceOf(IllegalArgumentException.class);
	}

	private static TenantConfigurationService service(TenantConfigurationPort port,
			OrganizationAdministrationPort scope) {
		return new TenantConfigurationService(port, scope, mock(SecurityAuditPort.class),
				Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
	}

	private static OperationalSettings settings(long version) { return settings(version, 3); }

	private static OperationalSettings settings(long version, int leadDays) {
		return new OperationalSettings("MANUAL", "WORKSPACE_HOURS", "STANDARD", "COARSE", "AVAILABLE_ONLY",
				LocalTime.of(8, 0), LocalTime.of(18, 0), 120, false, leadDays, version);
	}

	private static TenantConfigurationModels.OperationalSettingsView view(int leadDays, long version) {
		return new TenantConfigurationModels.OperationalSettingsView(WORKSPACE.toString(), "MANUAL",
				"WORKSPACE_HOURS", "STANDARD", "COARSE", "AVAILABLE_ONLY", LocalTime.of(8, 0),
				LocalTime.of(18, 0), 120, false, leadDays, version);
	}
}
