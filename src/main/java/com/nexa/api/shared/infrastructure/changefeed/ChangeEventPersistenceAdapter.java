package com.nexa.api.shared.infrastructure.changefeed;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
@Profile("!test")
public class ChangeEventPersistenceAdapter implements ChangeEventPersistencePort {
	private final JdbcTemplate jdbc;
	public ChangeEventPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public void append(String tenantId, String workspaceId, String clientAccountId, String aggregateType,
			String aggregateId, String eventType, String payload, long occurredAtEpochMillis) {
		jdbc.update("insert into integration.change_event (event_id,tenant_id,workspace_id,client_account_id,aggregate_type,aggregate_id,event_type,public_status,occurred_at,expires_at) values (?,?,?,?,?,?,?,?,?,?)",
				UUID.randomUUID(), UUID.fromString(tenantId), UUID.fromString(workspaceId), clientAccountId == null ? null : UUID.fromString(clientAccountId),
				aggregateType, UUID.fromString(aggregateId), eventType, publicStatus(payload),
				Timestamp.from(Instant.ofEpochMilli(occurredAtEpochMillis)),
				Timestamp.from(Instant.ofEpochMilli(occurredAtEpochMillis).plus(java.time.Duration.ofDays(14))));
	}

	@Scheduled(fixedDelayString = "${nexa.change-feed.cleanup-delay-ms:3600000}", initialDelayString = "${nexa.change-feed.cleanup-initial-delay-ms:3600000}")
	void removeExpiredBatch() {
		jdbc.update("delete from integration.change_event where \"sequence\" in (select \"sequence\" from integration.change_event where expires_at < current_timestamp order by expires_at, \"sequence\" limit 1000)");
	}

	private static String publicStatus(String payload) {
		if (payload == null) return null;
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"status\\\"\\s*:\\s*\\\"([A-Za-z0-9_ -]{1,48})\\\"").matcher(payload);
		return matcher.find() ? matcher.group(1) : null;
	}
}
