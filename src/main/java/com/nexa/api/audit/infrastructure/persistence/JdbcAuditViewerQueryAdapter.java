package com.nexa.api.audit.infrastructure.persistence;

import com.nexa.api.audit.application.model.AuditModels.AuditEventRecord;
import com.nexa.api.audit.application.port.out.AuditViewerQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAuditViewerQueryAdapter implements AuditViewerQueryPort {
	private static final String SELECT = "select id,tenant_id,workspace_id,actor_membership_id,actor_work_area,event_type,subject_type,subject_id,correlation_id,safe_metadata,occurred_at from audit.event";
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public JdbcAuditViewerQueryAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Override
	public List<AuditEventRecord> list(String tenantId, String workspaceId, int limit) {
		return jdbc.query(SELECT + " where tenant_id=? and workspace_id=? order by occurred_at desc,id desc limit ?",
				(rs, row) -> record(rs), uuid(tenantId), uuid(workspaceId), Math.min(100, Math.max(1, limit)));
	}

	@Override
	public Optional<AuditEventRecord> find(String tenantId, String workspaceId, String id) {
		return jdbc.query(SELECT + " where tenant_id=? and workspace_id=? and id=?",
				rs -> rs.next() ? Optional.of(record(rs)) : Optional.empty(), uuid(tenantId), uuid(workspaceId), uuid(id));
	}

	private AuditEventRecord record(ResultSet rs) throws java.sql.SQLException {
		return new AuditEventRecord(rs.getObject(1).toString(), rs.getObject(2).toString(), rs.getObject(3).toString(),
				string(rs, 4), rs.getString(5), rs.getString(6), rs.getString(7), string(rs, 8), rs.getString(9),
				rs.getTimestamp(11).toInstant(), metadata(rs.getString(10)));
	}

	private Map<String, Object> metadata(String json) {
		if (json == null || json.isBlank()) return Map.of();
		try {
			return mapper.readValue(json, new TypeReference<>() { });
		} catch (JacksonException exception) {
			return Map.of();
		}
	}

	private static String string(ResultSet rs, int index) throws java.sql.SQLException {
		Object value = rs.getObject(index);
		return value == null ? null : value.toString();
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
}
