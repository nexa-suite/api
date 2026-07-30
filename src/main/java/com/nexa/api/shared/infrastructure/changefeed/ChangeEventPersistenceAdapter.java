package com.nexa.api.shared.infrastructure.changefeed;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Profile;
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
		jdbc.update("insert into integration.change_event (tenant_id,workspace_id,client_account_id,aggregate_type,aggregate_id,event_type,payload,occurred_at) values (?,?,?,?,?,?,?::jsonb,?)",
				UUID.fromString(tenantId), UUID.fromString(workspaceId), clientAccountId == null ? null : UUID.fromString(clientAccountId),
				aggregateType, aggregateId, eventType, payload == null ? "{}" : payload, Timestamp.from(Instant.ofEpochMilli(occurredAtEpochMillis)));
	}
}
