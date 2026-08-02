package com.nexa.api.iam.infrastructure.security;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcSecurityAuditAdapter implements SecurityAuditPort {
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public JdbcSecurityAuditAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Override
	public void append(Event event) {
		try {
			jdbc.update("""
				INSERT INTO iam.security_audit_event
				(id, event_type, actor_user_id, target_user_id, tenant_id, workspace_id, surface,
				 correlation_id, trace_id, occurred_at, metadata_json)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
				""", ps -> {
					ps.setObject(1, java.util.UUID.randomUUID(), Types.OTHER);
					ps.setString(2, event.type());
					ps.setObject(3, event.actorUserId(), Types.OTHER);
					ps.setObject(4, event.targetUserId(), Types.OTHER);
					ps.setObject(5, event.tenantId(), Types.OTHER);
					ps.setObject(6, event.workspaceId(), Types.OTHER);
					ps.setString(7, event.surface());
					ps.setString(8, event.correlationId());
					ps.setString(9, event.traceId());
					ps.setTimestamp(10, java.sql.Timestamp.from(event.occurredAt()));
					ps.setString(11, mapper.writeValueAsString(event.metadata() == null ? java.util.Map.of() : event.metadata()));
				});
		} catch (JacksonException exception) {
			throw new IllegalArgumentException("Security audit metadata is not serializable", exception);
		}
	}
}
