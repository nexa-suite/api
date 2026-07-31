package com.nexa.api.shared.infrastructure.changefeed;

import com.nexa.api.shared.application.changefeed.ChangeEventAudience;
import com.nexa.api.shared.application.changefeed.ChangeEventAudiences;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Profile("!test")
public class ChangeEventPersistenceAdapter implements ChangeEventPersistencePort {
	private final JdbcTemplate jdbc;
	public ChangeEventPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public void append(String tenantId, String workspaceId, String clientAccountId, String aggregateType,
			String aggregateId, String eventType, String publicStatus, long occurredAtEpochMillis,
			boolean explicitBuyerVisibility) {
		String audiences = ChangeEventAudiences.forEvent(eventType, explicitBuyerVisibility).stream()
				.map(ChangeEventAudience::name).sorted().collect(Collectors.joining(",", "{", "}"));
		jdbc.update("insert into integration.change_event (event_id,tenant_id,workspace_id,client_account_id,aggregate_type,aggregate_id,event_type,public_status,audiences,occurred_at,expires_at) values (?,?,?,?,?,?,?,?,?::text[],?,?)",
				UUID.randomUUID(), UUID.fromString(tenantId), UUID.fromString(workspaceId), clientAccountId == null ? null : UUID.fromString(clientAccountId),
				aggregateType, UUID.fromString(aggregateId), eventType, publicStatus, audiences,
				Timestamp.from(Instant.ofEpochMilli(occurredAtEpochMillis)),
				Timestamp.from(Instant.ofEpochMilli(occurredAtEpochMillis).plus(java.time.Duration.ofDays(14))));
	}

	@Scheduled(fixedDelayString = "${nexa.change-feed.cleanup-delay-ms:3600000}", initialDelayString = "${nexa.change-feed.cleanup-initial-delay-ms:3600000}")
	void removeExpiredBatch() {
		jdbc.queryForObject("select integration.purge_expired_change_events(?)", Long.class, 1000);
	}
}
