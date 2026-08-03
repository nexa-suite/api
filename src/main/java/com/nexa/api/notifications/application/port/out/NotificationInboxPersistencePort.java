package com.nexa.api.notifications.application.port.out;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPage;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationView;
import com.nexa.api.notifications.application.model.NotificationModels.ProjectedNotification;
import java.util.Optional;

public interface NotificationInboxPersistencePort {
	NotificationPage find(String tenantId, String workspaceId, String recipientMembershipId, boolean unreadOnly, int limit);
	long unreadCount(String tenantId, String workspaceId, String recipientMembershipId);
	Optional<NotificationView> findOne(String tenantId, String workspaceId, String recipientMembershipId, String notificationId);
	int setRead(String tenantId, String workspaceId, String recipientMembershipId, String notificationId, boolean read);
	int setAllRead(String tenantId, String workspaceId, String recipientMembershipId);
	int insertIfAbsent(ProjectedNotification notification);
}
