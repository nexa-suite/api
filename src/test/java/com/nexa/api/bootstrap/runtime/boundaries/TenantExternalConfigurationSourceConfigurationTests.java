package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.notifications.application.publicapi.NotificationPreferenceAccess;
import com.nexa.api.notifications.domain.model.NotificationPreferenceInvariantViolation;
import com.nexa.api.salescommitment.application.publicapi.SalesUsageQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.TenantExternalConfigurationSource;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantExternalConfigurationSourceConfigurationTests {
	private static final UUID WORKSPACE_ID = UUID.fromString("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17");

	@Test
	void tenantCompatibilityBoundaryDelegatesValidationAndWritesToNotificationOwnerContract() {
		NotificationPreferenceAccess notifications = mock(NotificationPreferenceAccess.class);
		when(notifications.updateNotificationPreference(WORKSPACE_ID,
				new NotificationPreferenceAccess.Preference("ORDER_STATUS", "IN_APP", false, 4))).thenReturn(1);
		TenantExternalConfigurationSource source = source(notifications);
		var compatibilityPreference = new TenantExternalConfigurationSource.Preference(
				" order_status ", " in_app ", false, 4);

		assertThat(source.validateNotificationPreference(compatibilityPreference))
				.isEqualTo(new TenantExternalConfigurationSource.Preference("ORDER_STATUS", "IN_APP", false, 4));
		assertThat(source.updateNotificationPreference(WORKSPACE_ID, compatibilityPreference)).isEqualTo(1);
		verify(notifications).updateNotificationPreference(WORKSPACE_ID,
				new NotificationPreferenceAccess.Preference("ORDER_STATUS", "IN_APP", false, 4));
	}

	@Test
	void tenantCompatibilityBoundaryUsesNotificationOwnerValidation() {
		NotificationPreferenceAccess notifications = mock(NotificationPreferenceAccess.class);
		TenantExternalConfigurationSource source = source(notifications);

		assertThatThrownBy(() -> source.validateNotificationPreference(
				new TenantExternalConfigurationSource.Preference("UNKNOWN", "EMAIL", true, 0)))
				.isInstanceOf(NotificationPreferenceInvariantViolation.class);
		verify(notifications, never()).updateNotificationPreference(any(), any());
	}

	private static TenantExternalConfigurationSource source(NotificationPreferenceAccess notifications) {
		return new TenantExternalConfigurationSourceConfiguration().tenantExternalConfigurationSource(notifications,
				mock(SalesUsageQuery.class));
	}
}
