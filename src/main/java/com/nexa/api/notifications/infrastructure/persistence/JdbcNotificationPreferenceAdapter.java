package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;
import com.nexa.api.notifications.application.port.out.NotificationPreferencePersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcNotificationPreferenceAdapter implements NotificationPreferencePersistencePort {
	private final JdbcTemplate jdbc;

	public JdbcNotificationPreferenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public List<NotificationPreferenceView> find(String tenantId, String workspaceId) {
		return jdbc.query("select p.event_category,p.channel,p.enabled,p.version from tenant_management.notification_preference p join tenant_management.workspace w on w.id=p.workspace_id where w.id=? and w.tenant_id=? order by p.event_category,p.channel",
				(rs, row) -> new NotificationPreferenceView(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getLong(4)), uuid(workspaceId), uuid(tenantId));
	}

	@Override
	public long version(String tenantId, String workspaceId) {
		Long value = jdbc.queryForObject("select coalesce(max(p.version),0) from tenant_management.notification_preference p join tenant_management.workspace w on w.id=p.workspace_id where w.id=? and w.tenant_id=?",
				Long.class, uuid(workspaceId), uuid(tenantId));
		return value == null ? 0 : value;
	}

	@Override
	public int update(String tenantId, String workspaceId, NotificationPreferenceView preference) {
		return jdbc.update("update tenant_management.notification_preference p set enabled=?,updated_at=current_timestamp,version=version+1 from tenant_management.workspace w where p.workspace_id=w.id and w.id=? and w.tenant_id=? and p.event_category=? and p.channel=? and p.version=?",
				preference.enabled(), uuid(workspaceId), uuid(tenantId), preference.eventCategory(), preference.channel(), preference.version());
	}

	@Override
	public boolean isEnabled(String tenantId, String workspaceId, String eventCategory, String channel) {
		List<Boolean> values = jdbc.query("select p.enabled from tenant_management.notification_preference p join tenant_management.workspace w on w.id=p.workspace_id where w.id=? and w.tenant_id=? and p.event_category=? and p.channel=?",
				(rs, row) -> rs.getBoolean(1), uuid(workspaceId), uuid(tenantId), eventCategory, channel);
		return values.isEmpty() || values.getFirst();
	}

	private static UUID uuid(String value) { return UUID.fromString(value); }
}
