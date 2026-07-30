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
	@Override public Optional<IdempotencyResult> find(String tenant, String workspace, String actor, String operation, String key) { return jdbc.query("select resource_id,response_version from sales.idempotency_record where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=?", rs -> rs.next() ? Optional.of(new IdempotencyResult(rs.getObject(1).toString(), rs.getLong(2))) : Optional.empty(), uuid(tenant), uuid(workspace), uuid(actor), operation, key); }
	@Override public void save(String tenant, String workspace, String actor, String operation, String key, String resource, long version, UUID id, long epoch) { jdbc.update("insert into sales.idempotency_record (id,tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,resource_id,response_version,created_at) values (?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,actor_membership_id,operation,idempotency_key) do nothing", id, uuid(tenant), uuid(workspace), uuid(actor), operation, key, uuid(resource), version, Timestamp.from(Instant.ofEpochMilli(epoch))); }
	private static UUID uuid(String value) { return UUID.fromString(value); }
}
