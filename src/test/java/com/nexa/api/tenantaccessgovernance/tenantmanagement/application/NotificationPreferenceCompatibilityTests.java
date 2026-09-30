package com.nexa.api.tenantaccessgovernance.tenantmanagement.application;

import com.nexa.api.notifications.application.publicapi.NotificationPreferenceAccess;
import com.nexa.api.notifications.domain.model.NotificationPreferenceInvariantViolation;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.TenantConfigurationModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.WorkspaceSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.TenantConfigurationPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service.TenantConfigurationService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPreferenceCompatibilityTests {
	private static final UUID TENANT_ID = UUID.fromString("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10");
	private static final UUID WORKSPACE_ID = UUID.fromString("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17");

	@Test
	void tenantFacadeUsesValidatedOwnerOutputForUpdatesAndResponse() {
		TenantConfigurationPort port = mock(TenantConfigurationPort.class);
		OrganizationAdministrationPort scope = mock(OrganizationAdministrationPort.class);
		SecurityAuditPort audit = mock(SecurityAuditPort.class);
		CurrentAccessContext context = context();
		var requestPreference = new TenantConfigurationModels.NotificationPreferenceView(" order_status ",
				" in_app ", false, 7);
		var validatedPreference = new TenantConfigurationModels.NotificationPreferenceView("ORDER_STATUS", "IN_APP",
				false, 7);
		when(scope.findWorkspace(TENANT_ID.toString(), WORKSPACE_ID.toString()))
				.thenReturn(Optional.of(new WorkspaceSummary(WORKSPACE_ID.toString(), TENANT_ID.toString(), "Main", "main", "ACTIVE", 0)));
		when(port.notificationVersion(WORKSPACE_ID.toString())).thenReturn(7L);
		when(port.validateNotificationPreference(requestPreference)).thenAnswer(invocation -> {
			var value = invocation.getArgument(0, TenantConfigurationModels.NotificationPreferenceView.class);
			var validated = new NotificationPreferenceAccess.Preference(value.eventCategory(), value.channel(),
					value.enabled(), value.version());
			return new TenantConfigurationModels.NotificationPreferenceView(validated.eventCategory(),
					validated.channel(), validated.enabled(), validated.version());
		});
		when(port.updateNotificationPreference(WORKSPACE_ID.toString(), validatedPreference)).thenReturn(1);

		var response = new TenantConfigurationService(port, scope, audit,
				Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC))
				.updateNotificationSettings(context, WORKSPACE_ID.toString(),
						new TenantConfigurationModels.NotificationSettingsView(List.of(requestPreference), 7), 7, "trace-1");

		assertThat(response.preferences()).containsExactly(new TenantConfigurationModels.NotificationPreferenceView(
				"ORDER_STATUS", "IN_APP", false, 8));
		assertThat(response.version()).isEqualTo(8);
		verify(port).validateNotificationPreference(requestPreference);
		verify(port).updateNotificationPreference(WORKSPACE_ID.toString(), validatedPreference);
	}

	@Test
	void invalidTenantFacadePreferenceStopsBeforeAnyOwnerWrite() {
		TenantConfigurationPort port = mock(TenantConfigurationPort.class);
		OrganizationAdministrationPort scope = mock(OrganizationAdministrationPort.class);
		CurrentAccessContext context = context();
		var invalidPreference = new TenantConfigurationModels.NotificationPreferenceView("UNKNOWN", "EMAIL", true, 0);
		when(scope.findWorkspace(TENANT_ID.toString(), WORKSPACE_ID.toString()))
				.thenReturn(Optional.of(new WorkspaceSummary(WORKSPACE_ID.toString(), TENANT_ID.toString(), "Main", "main", "ACTIVE", 0)));
		when(port.notificationVersion(WORKSPACE_ID.toString())).thenReturn(0L);
		when(port.validateNotificationPreference(invalidPreference)).thenThrow(
				new NotificationPreferenceInvariantViolation("Unsupported notification category"));

		assertThatThrownBy(() -> new TenantConfigurationService(port, scope, mock(SecurityAuditPort.class),
				Clock.systemUTC()).updateNotificationSettings(context, WORKSPACE_ID.toString(),
						new TenantConfigurationModels.NotificationSettingsView(List.of(invalidPreference), 0), 0, "trace-2"))
				.isInstanceOf(NotificationPreferenceInvariantViolation.class);
		verify(port, never()).updateNotificationPreference(any(), any());
	}

	private static CurrentAccessContext context() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		when(context.tenantId()).thenReturn(new TenantId(TENANT_ID));
		when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE_ID));
		when(context.userId()).thenReturn(new UserId(UUID.fromString("24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2")));
		when(context.surface()).thenReturn(Surface.PLATFORM);
		return context;
	}
}
