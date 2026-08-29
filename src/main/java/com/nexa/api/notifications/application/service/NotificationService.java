package com.nexa.api.notifications.application.service;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPage;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.notifications.application.model.NotificationModels.ProjectedNotification;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;
import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.application.port.out.NotificationInboxPersistencePort;
import com.nexa.api.notifications.application.port.out.NotificationPreferencePersistencePort;
import com.nexa.api.notifications.application.port.out.PushNotificationOutboxPort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Locale;
import java.util.Objects;

public final class NotificationService implements NotificationUseCase, NotificationProjectionPort {
	private final NotificationInboxPersistencePort inbox;
	private final NotificationPreferencePersistencePort preferences;
	private final CustomerAccountQuery accounts;
	private final PushRoutingService pushRouting;
	private final ApplicationEventPublisher eventPublisher;
	private final PushNotificationOutboxPort pushOutbox;

	public NotificationService(NotificationInboxPersistencePort inbox, NotificationPreferencePersistencePort preferences,
			CustomerAccountQuery accounts) {
		this(inbox, preferences, accounts, null, null, null);
	}

	public NotificationService(NotificationInboxPersistencePort inbox, NotificationPreferencePersistencePort preferences,
			CustomerAccountQuery accounts, PushRoutingService pushRouting) {
		this(inbox, preferences, accounts, pushRouting, null, null);
	}

	public NotificationService(NotificationInboxPersistencePort inbox, NotificationPreferencePersistencePort preferences,
			CustomerAccountQuery accounts, PushRoutingService pushRouting, ApplicationEventPublisher eventPublisher) {
		this(inbox, preferences, accounts, pushRouting, eventPublisher, null);
	}

	public NotificationService(NotificationInboxPersistencePort inbox, NotificationPreferencePersistencePort preferences,
			CustomerAccountQuery accounts, PushRoutingService pushRouting, ApplicationEventPublisher eventPublisher,
			PushNotificationOutboxPort pushOutbox) {
		this.inbox = Objects.requireNonNull(inbox, "Notification inbox is required");
		this.preferences = Objects.requireNonNull(preferences, "Notification preferences are required");
		this.accounts = Objects.requireNonNull(accounts, "Client accounts are required");
		this.pushRouting = pushRouting;
		this.eventPublisher = eventPublisher;
		this.pushOutbox = pushOutbox;
	}

	@Override
	public NotificationPage inbox(CurrentAccessContext context, boolean unreadOnly, int limit) {
		Scope scope = scope(context);
		int safeLimit = Math.min(100, Math.max(1, limit));
		return inbox.find(scope.tenantId(), scope.workspaceId(), scope.membershipId(), unreadOnly, safeLimit);
	}

	@Override
	public long unreadCount(CurrentAccessContext context) {
		Scope scope = scope(context);
		return inbox.unreadCount(scope.tenantId(), scope.workspaceId(), scope.membershipId());
	}

	@Override
	public void markRead(CurrentAccessContext context, String notificationId, boolean read) {
		Scope scope = scope(context);
		if (inbox.setRead(scope.tenantId(), scope.workspaceId(), scope.membershipId(), notificationId, read) != 1) {
			throw new ApiResourceNotFoundException("notification");
		}
	}

	@Override
	public void markAllRead(CurrentAccessContext context) {
		Scope scope = scope(context);
		inbox.setAllRead(scope.tenantId(), scope.workspaceId(), scope.membershipId());
	}

	@Override
	public NotificationPreferencesView preferences(CurrentAccessContext context) {
		context.requirePermission(PermissionKey.NOTIFICATION_READ);
		String tenant = context.tenantId().toString();
		String workspace = context.workspaceId().toString();
		return new NotificationPreferencesView(preferences.find(tenant, workspace), preferences.version(tenant, workspace));
	}

	@Override
	public NotificationPreferencesView updatePreferences(CurrentAccessContext context, NotificationPreferencesView request) {
		context.requirePermission(PermissionKey.NOTIFICATION_MANAGE_PREFERENCES);
		Objects.requireNonNull(request, "Notification preferences are required");
		String tenant = context.tenantId().toString();
		String workspace = context.workspaceId().toString();
		if (preferences.version(tenant, workspace) != request.version()) throw new IllegalStateException("Notification preferences changed");
		for (NotificationPreferenceView preference : request.preferences()) {
			if (preferences.update(tenant, workspace, preference) != 1) throw new IllegalStateException("Notification preferences changed");
		}
		return preferences(context);
	}

	@Override
	public void project(NotificationProjection event) {
		Objects.requireNonNull(event, "Notification projection event is required");
		String category = category(event.eventType());
		String title = title(event.eventType());
		String body = body(event.eventType(), event.publicStatus());
		String deepLink = deepLink(event.eventType(), event.aggregateId());
		boolean inAppEnabled = preferences.isEnabled(event.tenantId(), event.workspaceId(), category, "IN_APP");
		for (String recipientMembershipId : event.recipientMembershipIds()) {
			if (inAppEnabled) {
				inbox.insertIfAbsent(new ProjectedNotification(event.eventId(), event.tenantId(), event.workspaceId(), recipientMembershipId,
						category, title, body, deepLink, event.aggregateType(), event.aggregateId(), event.occurredAt()));
			}
		}
		if (pushRouting != null) {
			var candidate = new com.nexa.api.notifications.application.model.NotificationModels.PushNotificationCandidate(
					event, category, title, body, deepLink);
			if (pushOutbox != null) pushOutbox.enqueue(candidate);
			else if (eventPublisher != null) eventPublisher.publishEvent(candidate);
			else pushRouting.route(event, category, title, body, deepLink);
		}
	}

	@Override
	public void deliverPush(com.nexa.api.notifications.application.model.NotificationModels.PushNotificationCandidate candidate) {
		Objects.requireNonNull(candidate, "Push notification candidate is required");
		if (pushRouting != null) {
			pushRouting.routeDurable(candidate.projection(), candidate.category(), candidate.title(), candidate.message(), candidate.deepLink());
		}
	}

	private Scope scope(CurrentAccessContext context) {
		String tenant = context.tenantId().toString();
		String workspace = context.workspaceId().toString();
		String client = null;
		if (context.hasRole(MembershipRole.BUYER)) {
			client = accounts.findBuyerReference(tenant, workspace, context.membershipId().toString())
					.map(CustomerAccountReference::id).orElseThrow(() -> new ApiResourceNotFoundException("client-account"));
		}
		context.requirePermission(PermissionKey.NOTIFICATION_READ);
		return new Scope(tenant, workspace, context.membershipId().toString(), client);
	}

	private static String category(String eventType) {
		String value = eventType == null ? "" : eventType.toLowerCase(Locale.ROOT);
		if (value.contains("temperature") || value.contains("incident")) return "TEMPERATURE_ALERT";
		if (value.contains("document") || value.contains("pod")) return "DOCUMENT_REMINDER";
		if (value.contains("invitation") || value.contains("membership")) return "INVITATION";
		return "ORDER_STATUS";
	}

	private static String title(String eventType) {
		String value = eventType == null ? "Notification" : eventType.replace('.', ' ').replace('-', ' ').strip();
		return cap(value.isBlank() ? "Notification" : capitalize(value), 240);
	}

	private static String body(String eventType, String status) {
		String value = title(eventType);
		String message = status == null || status.isBlank() ? value + " was recorded." : value + " is now " + cap(status, 160) + ".";
		return cap(message, 2000);
	}
	private static String deepLink(String eventType, String aggregateId) {
		if (aggregateId == null || aggregateId.isBlank()) return null;
		try { java.util.UUID.fromString(aggregateId); } catch (IllegalArgumentException exception) { return null; }
		String prefix = eventType == null ? "" : eventType.toLowerCase(Locale.ROOT);
		if (prefix.startsWith("sales.sales-order")) return "/sales-orders/" + aggregateId;
		if (prefix.startsWith("sales.purchase-request")) return "/purchase-requests/" + aggregateId;
		if (prefix.startsWith("logistics.")) return "/dispatch-orders/" + aggregateId;
		if (prefix.startsWith("warehouse.")) return "/warehouse/" + aggregateId;
		if (prefix.startsWith("organization.")) return "/settings/organization";
		return null;
	}
	private static String capitalize(String value) { return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1); }
	private static String cap(String value, int max) {
		String safe = value.replace('\r', ' ').replace('\n', ' ').strip();
		return safe.length() <= max ? safe : safe.substring(0, max);
	}

	private record Scope(String tenantId, String workspaceId, String membershipId, String clientAccountId) { }
}
