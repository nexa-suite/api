package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferenceView;
import com.nexa.api.notifications.application.port.out.NotificationPreferencePersistencePort;
import com.nexa.api.notifications.application.publicapi.NotificationPreferenceAccess;
import com.nexa.api.notifications.domain.model.NotificationPreference;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcNotificationPreferenceAdapter implements NotificationPreferencePersistencePort,
        NotificationPreferenceAccess {
	private final JdbcTemplate jdbc;
	private final WorkspaceDirectory workspaces;

	public JdbcNotificationPreferenceAdapter(JdbcTemplate jdbc, WorkspaceDirectory workspaces) {
		this.jdbc = jdbc;
		this.workspaces = workspaces;
	}

	@Override
	public List<NotificationPreferenceView> find(String tenantId, String workspaceId) {
		UUID workspace = uuid(workspaceId);
		UUID tenant = uuid(tenantId);
		if (!workspaces.exists(tenant, workspace)) return List.of();
		return jdbc.query("select p.event_category,p.channel,p.enabled,p.version from tenant_management.notification_preference p where p.workspace_id=? order by p.event_category,p.channel",
				(rs, row) -> new NotificationPreferenceView(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getLong(4)), workspace);
	}

	@Override
	public long version(String tenantId, String workspaceId) {
		UUID workspace = uuid(workspaceId);
		UUID tenant = uuid(tenantId);
		if (!workspaces.exists(tenant, workspace)) return 0;
		Long value = jdbc.queryForObject("select coalesce(max(p.version),0) from tenant_management.notification_preference p where p.workspace_id=?",
				Long.class, workspace);
		return value == null ? 0 : value;
	}

	@Override
	public int update(String tenantId, String workspaceId, NotificationPreferenceView preference) {
		UUID workspace = uuid(workspaceId);
		UUID tenant = uuid(tenantId);
		if (!workspaces.exists(tenant, workspace)) return 0;
		return jdbc.update("update tenant_management.notification_preference set enabled=?,updated_at=current_timestamp,version=version+1 where workspace_id=? and event_category=? and channel=? and version=?",
				preference.enabled(), workspace, preference.eventCategory(), preference.channel(), preference.version());
	}

	@Override
	public boolean isEnabled(String tenantId, String workspaceId, String eventCategory, String channel) {
		UUID workspace = uuid(workspaceId);
		UUID tenant = uuid(tenantId);
		if (!workspaces.exists(tenant, workspace)) return true;
		List<Boolean> values = jdbc.query("select p.enabled from tenant_management.notification_preference p where p.workspace_id=? and p.event_category=? and p.channel=?",
				(rs, row) -> rs.getBoolean(1), workspace, eventCategory, channel);
		return values.isEmpty() || values.getFirst();
	}

	@Override
	public List<NotificationPreferenceAccess.Preference> notificationPreferences(UUID workspaceId) {
		return List.copyOf(jdbc.query("select event_category,channel,enabled,version from tenant_management.notification_preference where workspace_id=? order by event_category,channel",
				(rs, row) -> new NotificationPreferenceAccess.Preference(
						rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getLong(4)), workspaceId));
	}

	@Override
	public long notificationVersion(UUID workspaceId) {
		Long value = jdbc.queryForObject("select coalesce(max(version),0) from tenant_management.notification_preference where workspace_id=?",
				Long.class, workspaceId);
		return value == null ? 0 : value;
	}

	@Override
	public int updateNotificationPreference(UUID workspaceId, NotificationPreferenceAccess.Preference preference) {
		return jdbc.update("update tenant_management.notification_preference set enabled=?,updated_at=current_timestamp,version=version+1 where workspace_id=? and event_category=? and channel=? and version=?",
				preference.enabled(), workspaceId, preference.eventCategory(), preference.channel(), preference.version());
	}

	@Override
	public void ensureNotificationDefaults(UUID workspaceId) {
		for (String category : NotificationPreference.eventCategories()) {
			for (String channel : NotificationPreference.channels()) {
				jdbc.update("insert into tenant_management.notification_preference (workspace_id,event_category,channel,enabled,version,updated_at) values (?,?,?,true,0,current_timestamp) on conflict (workspace_id,event_category,channel) do nothing",
						workspaceId, category, channel);
			}
		}
	}

	private static UUID uuid(String value) { return UUID.fromString(value); }
}
