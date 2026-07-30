package com.nexa.api.sales.infrastructure.purchaserequest;

import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestEventPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
@Profile("!test")
public class PurchaseRequestEventPersistenceAdapter implements PurchaseRequestEventPersistencePort {
	private final JdbcTemplate jdbc;
	public PurchaseRequestEventPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	@Override public void append(UUID eventId, String purchaseRequestId, String tenantId, String workspaceId, String actorMembershipId, String eventType, String fromStatus, String toStatus, long epoch) {
		jdbc.update("insert into sales.purchase_request_event (id,purchase_request_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,occurred_at) values (?,?,?,?,?,?,?,?,?)", eventId, uuid(purchaseRequestId), uuid(tenantId), uuid(workspaceId), uuid(actorMembershipId), eventType, fromStatus, toStatus, Timestamp.from(Instant.ofEpochMilli(epoch)));
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
}
