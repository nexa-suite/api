package com.nexa.api.notifications.application.port.in;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPage;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

public interface NotificationUseCase {
	NotificationPage inbox(CurrentAccessContext context, boolean unreadOnly, int limit);
	long unreadCount(CurrentAccessContext context);
	void markRead(CurrentAccessContext context, String notificationId, boolean read);
	void markAllRead(CurrentAccessContext context);
	NotificationPreferencesView preferences(CurrentAccessContext context);
	NotificationPreferencesView updatePreferences(CurrentAccessContext context, NotificationPreferencesView request);
}
