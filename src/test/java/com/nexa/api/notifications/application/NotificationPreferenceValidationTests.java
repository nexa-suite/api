package com.nexa.api.notifications.application;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.notifications.application.port.out.NotificationInboxPersistencePort;
import com.nexa.api.notifications.application.port.out.NotificationPreferencePersistencePort;
import com.nexa.api.notifications.application.service.NotificationService;
import com.nexa.api.notifications.domain.model.NotificationPreferenceInvariantViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPreferenceValidationTests {
	private static final String TENANT_ID = "3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10";
	private static final String WORKSPACE_ID = "7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17";

	@Test
	void notificationApiNormalizesAndReturnsOwnerValidatedPreference() {
		NotificationInboxPersistencePort inbox = mock(NotificationInboxPersistencePort.class);
		NotificationPreferencePersistencePort preferences = mock(NotificationPreferencePersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		CurrentAccessContext context = context();
		when(preferences.version(TENANT_ID, WORKSPACE_ID)).thenReturn(5L, 6L);
		when(preferences.update(TENANT_ID, WORKSPACE_ID,
				new NotificationPreferenceView("ORDER_STATUS", "IN_APP", false, 5))).thenReturn(1);
		when(preferences.find(TENANT_ID, WORKSPACE_ID))
				.thenReturn(List.of(new NotificationPreferenceView("ORDER_STATUS", "IN_APP", false, 6)));

		var response = new NotificationService(inbox, preferences, accounts).updatePreferences(context,
				new NotificationPreferencesView(List.of(new NotificationPreferenceView(" order_status ", " in_app ", false, 5)), 5));

		assertThat(response.preferences()).containsExactly(new NotificationPreferenceView("ORDER_STATUS", "IN_APP", false, 6));
		assertThat(response.version()).isEqualTo(6);
		verify(preferences).update(TENANT_ID, WORKSPACE_ID,
				new NotificationPreferenceView("ORDER_STATUS", "IN_APP", false, 5));
	}

	@Test
	void notificationApiRejectsUnsupportedCategoryBeforePersistence() {
		NotificationInboxPersistencePort inbox = mock(NotificationInboxPersistencePort.class);
		NotificationPreferencePersistencePort preferences = mock(NotificationPreferencePersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		when(preferences.version(TENANT_ID, WORKSPACE_ID)).thenReturn(0L);

		assertThatThrownBy(() -> new NotificationService(inbox, preferences, accounts).updatePreferences(context(),
				new NotificationPreferencesView(List.of(new NotificationPreferenceView("UNKNOWN", "EMAIL", true, 0)), 0)))
				.isInstanceOf(NotificationPreferenceInvariantViolation.class);

		verify(preferences, never()).update(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void notificationApiValidatesWholePreferenceListBeforePersistence() {
		NotificationInboxPersistencePort inbox = mock(NotificationInboxPersistencePort.class);
		NotificationPreferencePersistencePort preferences = mock(NotificationPreferencePersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		when(preferences.version(TENANT_ID, WORKSPACE_ID)).thenReturn(0L);
		when(preferences.update(TENANT_ID, WORKSPACE_ID,
				new NotificationPreferenceView("ORDER_STATUS", "IN_APP", true, 0))).thenReturn(1);

		assertThatThrownBy(() -> new NotificationService(inbox, preferences, accounts).updatePreferences(context(),
				new NotificationPreferencesView(List.of(
						new NotificationPreferenceView("ORDER_STATUS", "IN_APP", true, 0),
						new NotificationPreferenceView("UNKNOWN", "EMAIL", true, 0)), 0)))
				.isInstanceOf(NotificationPreferenceInvariantViolation.class);

		verify(preferences, never()).update(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
	}

	private static CurrentAccessContext context() {
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		when(context.tenantId()).thenReturn(new TenantId(TENANT_ID));
		when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE_ID));
		return context;
	}
}
