package com.nexa.api.notifications.application.port.out;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;

import java.util.List;

public interface NotificationPreferencePersistencePort {
	List<NotificationPreferenceView> find(String tenantId, String workspaceId);
	long version(String tenantId, String workspaceId);
	int update(String tenantId, String workspaceId, NotificationPreferenceView preference);
	boolean isEnabled(String tenantId, String workspaceId, String eventCategory, String channel);
}
