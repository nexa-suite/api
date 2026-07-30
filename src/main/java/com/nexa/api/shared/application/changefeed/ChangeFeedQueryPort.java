package com.nexa.api.shared.application.changefeed;

import java.util.List;

public interface ChangeFeedQueryPort {
	long minimumId(String tenantId, String workspaceId, String clientAccountId);
	List<ChangeEventView> after(String tenantId, String workspaceId, String clientAccountId, long lastEventId, int limit);
}
