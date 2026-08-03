package com.nexa.api.notifications.application.port.in;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;

/** Inbound boundary for the integration-event projector owned by the primary application. */
public interface NotificationProjectionPort {
	void project(NotificationProjection event);
}
