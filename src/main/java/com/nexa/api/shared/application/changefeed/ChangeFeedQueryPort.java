package com.nexa.api.shared.application.changefeed;

import java.util.List;
import java.util.Set;

public interface ChangeFeedQueryPort {
	/**
	 * Returns the oldest retained sequence in the stream scope, independent of
	 * audience. Sequence gaps are global and may belong to another audience;
	 * applying an audience filter here would produce false replay-expired results.
	 */
	long minimumId(String tenantId, String workspaceId, String clientAccountId);
	List<ChangeEventView> after(String tenantId, String workspaceId, String clientAccountId, Set<ChangeEventAudience> audiences, long lastEventId, int limit);
	default List<ChangeEventView> after(String tenantId, String workspaceId, String clientAccountId, ChangeEventAudience audience, long lastEventId, int limit) { return after(tenantId, workspaceId, clientAccountId, Set.of(audience), lastEventId, limit); }
}
