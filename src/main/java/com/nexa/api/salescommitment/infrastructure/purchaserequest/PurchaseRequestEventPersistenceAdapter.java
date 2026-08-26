package com.nexa.api.salescommitment.infrastructure.purchaserequest;

import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestEventPersistencePort;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;

@Repository
@Profile("!test")
public class PurchaseRequestEventPersistenceAdapter implements PurchaseRequestEventPersistencePort {
	private final JdbcTemplate jdbc;
	public PurchaseRequestEventPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	@Override public void append(UUID eventId, String purchaseRequestId, String tenantId, String workspaceId, String actorMembershipId, String eventType, String fromStatus, String toStatus, long epoch) {
		jdbc.update("insert into sales.purchase_request_event (id,purchase_request_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,occurred_at) values (?,?,?,?,?,?,?,?,?)", eventId, uuid(purchaseRequestId), uuid(tenantId), uuid(workspaceId), uuid(actorMembershipId), eventType, fromStatus, toStatus, Timestamp.from(Instant.ofEpochMilli(epoch)));
	}
	@Override public void appendCanonical(String eventType, String purchaseRequestId, String tenantId, String workspaceId,
			String correlationId, UUID causationId, Map<String, Object> payload, long epoch) {
		appendCanonical(eventType, purchaseRequestId, tenantId, workspaceId, correlationId, causationId, null, payload, epoch);
	}
	@Override public void appendCanonical(String eventType, String purchaseRequestId, String tenantId, String workspaceId,
			String correlationId, UUID causationId, String occurrenceKey, Map<String, Object> payload, long epoch) {
			CanonicalOutbox.append(jdbc, eventType, "PurchaseRequest", uuid(purchaseRequestId), uuid(tenantId), uuid(workspaceId),
					Instant.ofEpochMilli(epoch), correlationId, causationId, "1.0", occurrenceKey, payload);
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
}
