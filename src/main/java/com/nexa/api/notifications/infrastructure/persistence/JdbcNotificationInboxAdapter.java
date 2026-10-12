package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPage;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationView;
import com.nexa.api.notifications.application.model.NotificationModels.ProjectedNotification;
import com.nexa.api.notifications.application.port.out.NotificationInboxPersistencePort;
import com.nexa.api.notifications.application.publicapi.PreflightedNotificationRecipients;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcNotificationInboxAdapter implements NotificationInboxPersistencePort {
	private final JdbcTemplate jdbc;
	private final WorkforceDirectory workforce;
	private final PreflightedNotificationRecipients tenantRecipients;

	@Autowired
	public JdbcNotificationInboxAdapter(JdbcTemplate jdbc, WorkforceDirectory workforce) {
		this.jdbc = jdbc;
		this.workforce = workforce;
		this.tenantRecipients = null;
	}

	public JdbcNotificationInboxAdapter(JdbcTemplate jdbc, PreflightedNotificationRecipients tenantRecipients) {
		this.jdbc = jdbc;
		this.workforce = null;
		this.tenantRecipients = java.util.Objects.requireNonNull(tenantRecipients,
				"Central recipient preflight is required for Tenant notification writes");
	}

	@Override
	public NotificationPage find(String tenantId, String workspaceId, String recipientMembershipId,
			boolean unreadOnly, int limit) {
		Scope scope = scope(tenantId, workspaceId, recipientMembershipId);
		StringBuilder sql = new StringBuilder("select id,category,title,message,deep_link,subject_type,subject_id,created_at,read_at from notifications.inbox_item where tenant_id=? and workspace_id=? and recipient_membership_id=?");
		List<Object> args = new ArrayList<>(List.of(scope.tenant(), scope.workspace(), scope.recipient()));
		if (unreadOnly) sql.append(" and read_at is null");
		sql.append(" order by created_at desc,id desc limit ?");
		args.add(Math.min(100, Math.max(1, limit)));
		List<NotificationView> items = jdbc.query(sql.toString(), (RowMapper<NotificationView>) (rs, rowNum) -> view(rs), args.toArray());
		return new NotificationPage(items, unreadCount(tenantId, workspaceId, recipientMembershipId), Math.min(100, Math.max(1, limit)));
	}

	@Override
	public long unreadCount(String tenantId, String workspaceId, String recipientMembershipId) {
		Scope scope = scope(tenantId, workspaceId, recipientMembershipId);
		Long value = jdbc.queryForObject("select count(*) from notifications.inbox_item where tenant_id=? and workspace_id=? and recipient_membership_id=? and read_at is null",
				Long.class, scope.tenant(), scope.workspace(), scope.recipient());
		return value == null ? 0 : value;
	}

	@Override
	public Optional<NotificationView> findOne(String tenantId, String workspaceId, String recipientMembershipId, String notificationId) {
		Scope scope = scope(tenantId, workspaceId, recipientMembershipId);
		return jdbc.query("select id,category,title,message,deep_link,subject_type,subject_id,created_at,read_at from notifications.inbox_item where tenant_id=? and workspace_id=? and recipient_membership_id=? and id=?",
				rs -> rs.next() ? Optional.of(view(rs)) : Optional.empty(), scope.tenant(), scope.workspace(), scope.recipient(), uuid(notificationId));
	}

	@Override
	public int setRead(String tenantId, String workspaceId, String recipientMembershipId, String notificationId, boolean read) {
		Scope scope = scope(tenantId, workspaceId, recipientMembershipId);
		return jdbc.update("update notifications.inbox_item set read_at=" + (read ? "current_timestamp" : "null")
				+ " where tenant_id=? and workspace_id=? and recipient_membership_id=? and id=?",
				scope.tenant(), scope.workspace(), scope.recipient(), uuid(notificationId));
	}

	@Override
	public int setAllRead(String tenantId, String workspaceId, String recipientMembershipId) {
		Scope scope = scope(tenantId, workspaceId, recipientMembershipId);
		return jdbc.update("update notifications.inbox_item set read_at=current_timestamp where tenant_id=? and workspace_id=? and recipient_membership_id=? and read_at is null",
				scope.tenant(), scope.workspace(), scope.recipient());
	}

	@Override
	public int insertIfAbsent(ProjectedNotification notification) {
		UUID tenantId = uuid(notification.tenantId());
		UUID workspaceId = uuid(notification.workspaceId());
		UUID recipientMembershipId = uuid(notification.recipientMembershipId());
		if (tenantRecipients != null) {
			if (!tenantRecipients.allows(tenantId, workspaceId, recipientMembershipId)) {
				throw new IllegalStateException("Notification recipient escaped its central Tenant preflight");
			}
		} else if (!workforce.membershipExists(tenantId, workspaceId, recipientMembershipId)) return 0;
		return jdbc.update("insert into notifications.inbox_item (id,tenant_id,workspace_id,recipient_membership_id,event_id,category,title,message,deep_link,subject_type,subject_id,created_at,read_at) "
				+ "values (?,?,?,?,?,?,?,?,?,?,?,?,null) "
				+ "on conflict (event_id,recipient_membership_id) do nothing",
				UUID.randomUUID(), tenantId, workspaceId, recipientMembershipId,
				uuid(notification.eventId()), notification.category(), notification.title(), notification.message(), notification.deepLink(),
				notification.subjectType(), uuidOrNull(notification.subjectId()), Timestamp.from(notification.createdAt()));
	}

	private NotificationView view(java.sql.ResultSet rs) throws java.sql.SQLException {
		Object subjectId = rs.getObject(7);
		return new NotificationView(rs.getObject(1).toString(), rs.getString(2), rs.getString(3), rs.getString(4),
				rs.getString(5), rs.getString(6), subjectId == null ? null : subjectId.toString(), rs.getTimestamp(8).toInstant(),
				rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant());
	}

	private Scope scope(String tenantId, String workspaceId, String recipientMembershipId) {
		UUID tenant = uuid(tenantId);
		UUID workspace = uuid(workspaceId);
		UUID recipient = uuid(recipientMembershipId);
		if (tenantRecipients != null && !tenantRecipients.allows(tenant, workspace, recipient)) {
			throw new IllegalStateException("Notification request escaped its verified Tenant/Workspace preflight");
		}
		return new Scope(tenant, workspace, recipient);
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
	private static UUID uuidOrNull(String value) { return value == null || value.isBlank() ? null : uuid(value); }
	private record Scope(UUID tenant, UUID workspace, UUID recipient) { }
}
