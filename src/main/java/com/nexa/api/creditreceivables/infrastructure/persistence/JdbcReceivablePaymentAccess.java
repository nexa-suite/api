package com.nexa.api.creditreceivables.infrastructure.persistence;

import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Receivable access remains under Credit & Receivables, including legacy storage. */
@Repository
@Profile("!test")
public class JdbcReceivablePaymentAccess implements ReceivablePaymentAccess {
    private static final String SELECT = "select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,due_at,version,created_at from payments.receivable ";
    private final JdbcTemplate jdbc;

    public JdbcReceivablePaymentAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot claimForPayment(UUID tenantId, UUID workspaceId, UUID receivableId) {
        return jdbc.query(SELECT + "where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> snapshot(rs), tenantId, workspaceId, receivableId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Receivable not found"));
    }

    @Override
    public Optional<Snapshot> find(UUID tenantId, UUID workspaceId, UUID receivableId) {
        return jdbc.query(SELECT + "where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> snapshot(rs), tenantId, workspaceId, receivableId).stream().findFirst();
    }

    @Override
    public Optional<Snapshot> findForSubject(UUID tenantId, UUID workspaceId, UUID subjectId, String subjectType) {
        return jdbc.query(SELECT + "where tenant_id=? and workspace_id=? and subject_id=? and subject_type=?",
                (rs, row) -> snapshot(rs), tenantId, workspaceId, subjectId, subjectType).stream().findFirst();
    }

    @Override
    public Page list(UUID tenantId, UUID workspaceId, UUID customerAccountId, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        List<Object> args = new ArrayList<>(List.of(tenantId, workspaceId));
        String where = "where tenant_id=? and workspace_id=?";
        if (customerAccountId != null) { where += " and client_account_id=?"; args.add(customerAccountId); }
        Long total = jdbc.queryForObject("select count(*) from payments.receivable " + where, Long.class, args.toArray());
        args.add(safeSize);
        args.add(Math.max(0, page) * safeSize);
        List<Snapshot> values = jdbc.query(SELECT + where + " order by due_at nulls last,created_at desc limit ? offset ?",
                (rs, row) -> snapshot(rs), args.toArray());
        return new Page(values, total == null ? 0 : total);
    }

    @Override
    public Map<UUID, Snapshot> findAll(UUID tenantId, UUID workspaceId, List<UUID> receivableIds) {
        if (receivableIds.isEmpty()) return Map.of();
        List<UUID> ids = receivableIds.stream().distinct().toList();
        List<Object> args = new ArrayList<>(List.of(tenantId, workspaceId));
        args.addAll(ids);
        Map<UUID, Snapshot> result = new LinkedHashMap<>();
        jdbc.query(SELECT + "where tenant_id=? and workspace_id=? and id in ("
                        + String.join(",", Collections.nCopies(ids.size(), "?")) + ")",
                (rs, row) -> snapshot(rs), args.toArray()).forEach(value -> result.put(value.id(), value));
        return Map.copyOf(result);
    }

    @Override
    public java.math.BigDecimal allocatedAmount(UUID tenantId, UUID workspaceId, UUID paymentId) {
        return jdbc.queryForObject("select coalesce(sum(amount),0) from payments.receivable_allocation "
                        + "where tenant_id=? and workspace_id=? and payment_id=?",
                java.math.BigDecimal.class, tenantId, workspaceId, paymentId);
    }

    private static Snapshot snapshot(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class),
                rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("receivable_number"),
                rs.getString("currency"), rs.getBigDecimal("amount"), rs.getBigDecimal("amount_paid"),
                rs.getBigDecimal("adjustment_total"), rs.getString("status"),
                rs.getTimestamp("due_at").toInstant(), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant());
    }
}
