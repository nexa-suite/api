package com.nexa.api.sales.infrastructure.idempotency;

import com.nexa.api.sales.application.purchaserequest.port.IdempotencyPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class IdempotencyPersistenceAdapter implements IdempotencyPersistencePort {
	private final JdbcTemplate jdbc;
	public IdempotencyPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	@Override public void lock(String tenant, String workspace, String actor, String operation, String key) {
		jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> null,
			tenant + "|" + workspace + "|" + actor + "|" + operation + "|" + key);
	}
	@Override public Optional<IdempotencyResult> find(String tenant, String workspace, String actor, String operation, String key) {
		return jdbc.query("select record.resource_id,record.response_version,coalesce(response.response_json,record.response_json) "
				+ "from sales.idempotency_record record left join sales.idempotency_response response "
				+ "on response.tenant_id=record.tenant_id and response.workspace_id=record.workspace_id "
				+ "and response.actor_membership_id=record.actor_membership_id and response.operation=record.operation "
				+ "and response.idempotency_key=record.idempotency_key "
				+ "where record.tenant_id=? and record.workspace_id=? and record.actor_membership_id=? and record.operation=? and record.idempotency_key=?",
			rs -> rs.next() ? Optional.of(new IdempotencyResult(rs.getObject(1).toString(), rs.getLong(2), rs.getString(3))) : Optional.empty(),
			uuid(tenant), uuid(workspace), uuid(actor), operation, key);
	}
	@Override public Optional<IdempotencyResult> find(String tenant, String workspace, String actor, String operation, String key, String requestHash) {
		return jdbc.query("select record.resource_id,record.response_version,record.request_hash,coalesce(response.response_json,record.response_json) "
				+ "from sales.idempotency_record record left join sales.idempotency_response response "
				+ "on response.tenant_id=record.tenant_id and response.workspace_id=record.workspace_id "
				+ "and response.actor_membership_id=record.actor_membership_id and response.operation=record.operation "
				+ "and response.idempotency_key=record.idempotency_key "
				+ "where record.tenant_id=? and record.workspace_id=? and record.actor_membership_id=? and record.operation=? and record.idempotency_key=?",
			rs -> {
				if (!rs.next()) return Optional.empty();
				String stored = rs.getString(3);
				if (stored != null && !stored.isBlank() && requestHash != null && !stored.equalsIgnoreCase(requestHash)) throw new com.nexa.api.sales.application.exception.SalesIdempotencyPayloadConflictException();
				return Optional.of(new IdempotencyResult(rs.getObject(1).toString(), rs.getLong(2), rs.getString(4)));
			}, uuid(tenant), uuid(workspace), uuid(actor), operation, key);
	}
	@Override public void save(String tenant, String workspace, String actor, String operation, String key, String resource, long version, UUID id, long epoch) {
		jdbc.update("insert into sales.idempotency_record (id,tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,resource_id,response_version,created_at) values (?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,actor_membership_id,operation,idempotency_key) do nothing",
				id, uuid(tenant), uuid(workspace), uuid(actor), operation, key, uuid(resource), version, Timestamp.from(Instant.ofEpochMilli(epoch)));
	}
	@Override public void save(String tenant, String workspace, String actor, String operation, String key, String resource, long version, UUID id, long epoch, String requestHash) {
		jdbc.update("insert into sales.idempotency_record (id,tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,resource_id,response_version,request_hash,created_at) values (?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,actor_membership_id,operation,idempotency_key) do nothing",
				id, uuid(tenant), uuid(workspace), uuid(actor), operation, key, uuid(resource), version, requestHash == null ? "" : requestHash, Timestamp.from(Instant.ofEpochMilli(epoch)));
	}
	@Override public void save(String tenant, String workspace, String actor, String operation, String key, String resource, long version, UUID id, long epoch, String requestHash, String responseJson) {
		save(tenant, workspace, actor, operation, key, resource, version, id, epoch, requestHash);
		if (responseJson != null) insertResponse(tenant, workspace, actor, operation, key, responseJson, epoch);
	}
	@Override public void updateResponse(String tenant, String workspace, String actor, String operation, String key, String responseJson) {
		if (responseJson != null) insertResponse(tenant, workspace, actor, operation, key, responseJson, System.currentTimeMillis());
	}
	private void insertResponse(String tenant, String workspace, String actor, String operation, String key, String responseJson, long epoch) {
		jdbc.update("insert into sales.idempotency_response (tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,response_json,created_at) values (?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,actor_membership_id,operation,idempotency_key) do nothing",
				uuid(tenant), uuid(workspace), uuid(actor), operation, key, responseJson, Timestamp.from(Instant.ofEpochMilli(epoch)));
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
}
