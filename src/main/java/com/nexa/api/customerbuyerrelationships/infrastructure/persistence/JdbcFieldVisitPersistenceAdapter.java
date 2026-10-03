package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.model.FieldVisitEvidence;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcFieldVisitPersistenceAdapter implements FieldVisitPersistencePort {
    private final JdbcTemplate jdbc;
    public JdbcFieldVisitPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void lockCustomer(String tenant, String workspace, String customer) {
        jdbc.query("select id from sales.client_account where tenant_id=? and workspace_id=? and id=? for update",
            (rs, row) -> rs.getObject(1), uuid(tenant), uuid(workspace), uuid(customer));
    }
    @Override public Optional<FieldVisitEvidence> replay(String tenant, String workspace, String membership, String key, String hash) {
        // Serialize identical keys even when callers target different customer rows.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", (rs, row) -> 1,
            tenant + ":" + workspace + ":" + membership + ":" + key);
        return jdbc.query("select * from sales.field_visit_evidence where tenant_id=? and workspace_id=? and recorded_by_membership_id=? and idempotency_key=?",
            (rs, row) -> { if (!hash.equals(rs.getString("payload_hash"))) throw new CustomerRelationshipConflictException(); return map(rs); },
            uuid(tenant), uuid(workspace), uuid(membership), key).stream().findFirst();
    }
    @Override public void insert(String tenant, String workspace, String key, String hash, FieldVisitEvidence e) {
        jdbc.update("insert into sales.field_visit_evidence (id,tenant_id,workspace_id,client_account_id,recorded_by_membership_id,customer_version,purpose,outcome,occurred_at,recorded_at,idempotency_key,payload_hash) values (?,?,?,?,?,?,?,?,?,?,?,?)",
            uuid(e.id()), uuid(tenant), uuid(workspace), uuid(e.clientAccountId()), uuid(e.recordedByMembershipId()), e.customerVersion(),
            e.purpose(), e.outcome(), Timestamp.from(e.occurredAt()), Timestamp.from(e.recordedAt()), key, hash);
    }
    @Override public List<FieldVisitEvidence> list(String tenant, String workspace, String customer) {
        return jdbc.query("select * from sales.field_visit_evidence where tenant_id=? and workspace_id=? and client_account_id=? order by recorded_at desc,id desc limit 100",
            (rs, row) -> map(rs), uuid(tenant), uuid(workspace), uuid(customer));
    }
    private FieldVisitEvidence map(ResultSet rs) throws SQLException {
        return new FieldVisitEvidence(rs.getObject("id").toString(),rs.getObject("client_account_id").toString(),
            rs.getObject("recorded_by_membership_id").toString(),rs.getLong("customer_version"),rs.getString("purpose"),rs.getString("outcome"),
            rs.getTimestamp("occurred_at").toInstant(),rs.getTimestamp("recorded_at").toInstant());
    }
    private UUID uuid(String value) { return UUID.fromString(value); }
}
