package com.nexa.api.notifications.application;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPage;
import com.nexa.api.notifications.application.port.out.NotificationInboxPersistencePort;
import com.nexa.api.notifications.application.port.out.NotificationPreferencePersistencePort;
import com.nexa.api.notifications.application.service.NotificationService;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;
import com.nexa.api.notifications.application.model.NotificationModels.PushNotificationCandidate;
import com.nexa.api.notifications.application.service.PushRoutingService;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class NotificationServiceIsolationTests {
	@Test
	void inboxUsesCurrentTenantWorkspaceAndMembershipScope() {
		NotificationInboxPersistencePort inbox = mock(NotificationInboxPersistencePort.class);
		NotificationPreferencePersistencePort preferences = mock(NotificationPreferencePersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		CurrentAccessContext context = mock(CurrentAccessContext.class);
		when(context.tenantId()).thenReturn(new TenantId("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10"));
		when(context.workspaceId()).thenReturn(new WorkspaceId("7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17"));
		when(context.membershipId()).thenReturn(new MembershipId("24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2"));
		when(inbox.find(context.tenantId().toString(), context.workspaceId().toString(), context.membershipId().toString(), true, 10))
				.thenReturn(new NotificationPage(List.of(), 0, 10));

		NotificationService service = new NotificationService(inbox, preferences, accounts);
		assertThat(service.inbox(context, true, 10).items()).isEmpty();

		verify(inbox).find("3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10", "7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17",
				"24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2", true, 10);
	}

	@Test
	void publishesPushCandidateWithoutCallingProviderInsideProjection() {
		NotificationInboxPersistencePort inbox = mock(NotificationInboxPersistencePort.class);
		NotificationPreferencePersistencePort preferences = mock(NotificationPreferencePersistencePort.class);
		CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
		PushRoutingService routing = mock(PushRoutingService.class);
		org.springframework.context.ApplicationEventPublisher publisher = mock(org.springframework.context.ApplicationEventPublisher.class);
		NotificationService service = new NotificationService(inbox, preferences, accounts, routing, publisher);
		NotificationProjection event = new NotificationProjection(UUID.randomUUID().toString(),
				"3a0a7af1-83ad-4c20-bb31-3ea89f4e4f10", "7c30dcf8-bf35-40dc-bd3d-fad4dd1b3a17", null,
				"SalesOrder", UUID.randomUUID().toString(), "SALES_ORDER_CONFIRMED", "CONFIRMED", Instant.now(),
				Set.of("24c5e28d-2249-4c12-b6d8-9d5f3e4f0cd2"));

		service.project(event);

		var candidate = org.mockito.ArgumentCaptor.forClass(PushNotificationCandidate.class);
		verify(publisher).publishEvent(candidate.capture());
		assertThat(candidate.getValue().projection()).isEqualTo(event);
		verify(routing, never()).route(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
	}
}
