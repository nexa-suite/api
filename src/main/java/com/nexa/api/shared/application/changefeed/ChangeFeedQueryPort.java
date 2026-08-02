package com.nexa.api.shared.application.changefeed;

import java.util.List;
import java.util.Set;

public interface ChangeFeedQueryPort {
	long minimumId(String tenantId, String workspaceId, String clientAccountId, Set<ChangeEventAudience> audiences);
	List<ChangeEventView> after(String tenantId, String workspaceId, String clientAccountId, Set<ChangeEventAudience> audiences, long lastEventId, int limit);
	default long minimumId(String tenantId, String workspaceId, String clientAccountId, ChangeEventAudience audience) { return minimumId(tenantId, workspaceId, clientAccountId, Set.of(audience)); }
	default List<ChangeEventView> after(String tenantId, String workspaceId, String clientAccountId, ChangeEventAudience audience, long lastEventId, int limit) { return after(tenantId, workspaceId, clientAccountId, Set.of(audience), lastEventId, limit); }
}
