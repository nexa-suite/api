package com.nexa.api.shared.infrastructure.changefeed;

import com.nexa.api.shared.application.changefeed.ChangeEventView;
import com.nexa.api.shared.application.changefeed.ChangeFeedQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
public class ChangeFeedQueryAdapter implements ChangeFeedQueryPort {
	private final JdbcTemplate jdbc;
	public ChangeFeedQueryAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public long minimumId(String tenantId, String workspaceId, String clientAccountId) {
		String scope = clientAccountId == null ? "" : " and client_account_id=?";
		List<Object> args = new ArrayList<>(List.of(UUID.fromString(tenantId), UUID.fromString(workspaceId)));
		if (clientAccountId != null) args.add(UUID.fromString(clientAccountId));
		Long value = jdbc.queryForObject("select coalesce(min(id),0) from integration.change_event where tenant_id=? and workspace_id=?" + scope, Long.class, args.toArray());
		return value == null ? 0 : value;
	}

	@Override
	public List<ChangeEventView> after(String tenantId, String workspaceId, String clientAccountId, long lastEventId, int limit) {
		String scope = clientAccountId == null ? "" : " and client_account_id=?";
		List<Object> args = new ArrayList<>(List.of(UUID.fromString(tenantId), UUID.fromString(workspaceId), lastEventId));
		if (clientAccountId != null) args.add(UUID.fromString(clientAccountId));
		args.add(Math.min(100, Math.max(1, limit)));
		return jdbc.query("select id,aggregate_type,aggregate_id,event_type,payload::text,occurred_at from integration.change_event where tenant_id=? and workspace_id=? and id>?" + scope + " order by id asc limit ?",
				(rs, row) -> new ChangeEventView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), args.toArray());
	}
}
