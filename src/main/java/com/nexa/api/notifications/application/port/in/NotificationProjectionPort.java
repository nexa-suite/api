package com.nexa.api.notifications.application.port.in;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;
import com.nexa.api.notifications.application.model.NotificationModels.PushNotificationCandidate;

/** Inbound boundary for the integration-event projector owned by the primary application. */
public interface NotificationProjectionPort {
	void project(NotificationProjection event);

	/** Routes a durable push work item after the source business transaction. */
	void deliverPush(PushNotificationCandidate candidate);
}
