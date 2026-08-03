package com.nexa.api.shared.infrastructure.changefeed;

import com.nexa.api.shared.application.changefeed.ChangeEventAudience;
import com.nexa.api.shared.application.changefeed.ChangeEventView;
import com.nexa.api.shared.application.changefeed.ChangeFeedQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
		Long value = jdbc.queryForObject("select coalesce(min(\"sequence\"),0) from integration.change_event where tenant_id=? and workspace_id=? and (expires_at is null or expires_at > current_timestamp)" + scope, Long.class, args.toArray());
		return value == null ? 0 : value;
	}

	@Override
	public List<ChangeEventView> after(String tenantId, String workspaceId, String clientAccountId, Set<ChangeEventAudience> audiences, long lastEventId, int limit) {
		if (audiences == null || audiences.isEmpty()) return List.of();
		String scope = clientAccountId == null ? "" : " and client_account_id=?";
		String audiencePlaceholders = String.join(",", audiences.stream().map(value -> "?").toList());
		List<Object> args = new ArrayList<>(List.of(UUID.fromString(tenantId), UUID.fromString(workspaceId)));
		args.addAll(audiences.stream().map(Enum::name).toList());
		args.add(lastEventId);
		if (clientAccountId != null) args.add(UUID.fromString(clientAccountId));
		args.add(Math.min(100, Math.max(1, limit)));
		return jdbc.query("select distinct \"sequence\",event_id,aggregate_type,aggregate_id,event_type,aggregate_version,public_status,occurred_at from integration.change_event where tenant_id=? and workspace_id=? and audiences && array[" + audiencePlaceholders + "]::text[] and (expires_at is null or expires_at > current_timestamp) and \"sequence\">?" + scope + " order by \"sequence\" asc limit ?",
				(rs, row) -> new ChangeEventView(rs.getLong(1), rs.getObject(2).toString(), rs.getString(3), rs.getObject(4).toString(), rs.getString(5), rs.getObject(6) == null ? null : rs.getLong(6), rs.getString(7), rs.getTimestamp(8).toInstant()), args.toArray());
	}
}
