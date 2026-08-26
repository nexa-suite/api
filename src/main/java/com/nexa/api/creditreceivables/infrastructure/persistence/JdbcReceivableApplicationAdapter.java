package com.nexa.api.creditreceivables.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableApplicationCommands;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Atomic receivable application owned by BC-07. */
@Repository
@Profile("!test")
public class JdbcReceivableApplicationAdapter implements ReceivableApplicationCommands {
    private final JdbcTemplate jdbc;
    private final BusinessTraceabilityCommands traceability;

    public JdbcReceivableApplicationAdapter(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability) {
        this.jdbc = jdbc;
        this.traceability = traceability;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(Request request) {
        lock(request);
        ReceivableRow receivable = lockReceivable(request);
        if (!receivable.currency().equalsIgnoreCase(request.currency())) throw error("RECEIVABLE_CURRENCY_MISMATCH");
        ExistingApplication existing = existing(request);
        if (existing != null) {
            if (existing.amount().compareTo(request.amount()) != 0 || !existing.currency().equalsIgnoreCase(request.currency())) {
                throw error("RECEIVABLE_APPLICATION_CONFLICT");
            }
            return result(existing.id(), receivable, request.paymentId());
        }
        BigDecimal adjustedAmount = receivable.amount().add(receivable.adjustmentTotal());
        BigDecimal nextPaid = receivable.amountPaid().add(request.amount());
        if (nextPaid.compareTo(adjustedAmount) > 0) throw error("RECEIVABLE_APPLICATION_EXCEEDS_BALANCE");
        UUID applicationId = UUID.randomUUID();
        int inserted = jdbc.update("insert into payments.receivable_application(id,tenant_id,workspace_id,receivable_id,payment_id,amount,currency,applied_at) values (?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,receivable_id,payment_id) do nothing",
                applicationId, request.tenantId(), request.workspaceId(), request.receivableId(), request.paymentId(),
                request.amount(), request.currency(), Timestamp.from(request.now()));
        if (inserted == 0) {
            ExistingApplication concurrent = existing(request);
            if (concurrent == null) throw error("RECEIVABLE_APPLICATION_CONFLICT");
            if (concurrent.amount().compareTo(request.amount()) != 0 || !concurrent.currency().equalsIgnoreCase(request.currency())) throw error("RECEIVABLE_APPLICATION_CONFLICT");
            return result(concurrent.id(), receivable, request.paymentId());
        }
        int legacyInserted = jdbc.update("insert into payments.receivable_allocation(id,tenant_id,workspace_id,receivable_id,payment_id,amount,allocated_at) values (?,?,?,?,?,?,?) on conflict (payment_id) do nothing",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.receivableId(), request.paymentId(),
                request.amount(), Timestamp.from(request.now()));
        if (legacyInserted == 0) {
            LegacyAllocation legacy = jdbc.query("select receivable_id,amount from payments.receivable_allocation where payment_id=?",
                    (rs, row) -> new LegacyAllocation(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), request.paymentId())
                    .stream().findFirst().orElseThrow(() -> error("RECEIVABLE_APPLICATION_CONFLICT"));
            if (!request.receivableId().equals(legacy.receivableId()) || legacy.amount().compareTo(request.amount()) != 0) throw error("RECEIVABLE_APPLICATION_CONFLICT");
        }
        String nextStatus = nextPaid.compareTo(adjustedAmount) == 0 ? "PAID" : (nextPaid.signum() == 0 ? "OPEN" : "PARTIALLY_PAID");
        if (jdbc.update("update payments.receivable set amount_paid=?,status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                nextPaid, nextStatus, Timestamp.from(request.now()), request.tenantId(), request.workspaceId(), request.receivableId(), receivable.version()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        CanonicalOutbox.append(jdbc, "ReceivablePosted.v1", "Receivable", request.receivableId(), request.tenantId(),
                request.workspaceId(), request.now(), request.occurrenceKey(), null, "1.0", request.paymentId().toString(),
                Map.of("receivableId", request.receivableId(), "paymentId", request.paymentId(), "amount", request.amount(), "currency", request.currency()));
        traceability.record(new BusinessTraceabilityCommands.TraceRequest(request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), "PAYMENTS", "RECEIVABLE_SETTLEMENT_CHANGED", "Receivable",
                request.receivableId(), request.occurrenceKey(), "receivable-settlement-" + request.paymentId(),
                Map.of("paymentId", request.paymentId(), "amount", request.amount(), "status", nextStatus), request.now()));
        return new Result(applicationId, request.receivableId(), request.paymentId(), nextPaid,
                adjustedAmount.subtract(nextPaid).max(BigDecimal.ZERO), nextStatus, receivable.version() + 1);
    }

    private Result result(UUID applicationId, ReceivableRow receivable, UUID paymentId) {
        BigDecimal adjusted = receivable.amount().add(receivable.adjustmentTotal());
        BigDecimal paid = receivable.amountPaid();
        return new Result(applicationId, receivable.id(), paymentId, paid, adjusted.subtract(paid).max(BigDecimal.ZERO), receivable.status(), receivable.version());
    }

    private ExistingApplication existing(Request request) {
        return jdbc.query("select id,amount,currency from payments.receivable_application where tenant_id=? and workspace_id=? and receivable_id=? and payment_id=?",
                (rs, row) -> new ExistingApplication(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency")),
                request.tenantId(), request.workspaceId(), request.receivableId(), request.paymentId()).stream().findFirst().orElse(null);
    }

    private ReceivableRow lockReceivable(Request request) {
        return jdbc.query("select id,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,currency,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new ReceivableRow(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getBigDecimal("amount_paid"),
                        rs.getBigDecimal("adjustment_total"), rs.getString("status"), rs.getString("currency"), rs.getLong("version")),
                request.tenantId(), request.workspaceId(), request.receivableId()).stream().findFirst().orElseThrow(() -> error("RECEIVABLE_NOT_FOUND"));
    }

    private void lock(Request request) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                request.tenantId() + "|" + request.workspaceId() + "|receivable-application|" + request.receivableId() + "|" + request.paymentId());
    }

    private static CreditReceivableOperationException error(String code) { return new CreditReceivableOperationException(code); }
    private record ReceivableRow(UUID id, BigDecimal amount, BigDecimal amountPaid, BigDecimal adjustmentTotal,
                                 String status, String currency, long version) { }
    private record ExistingApplication(UUID id, BigDecimal amount, String currency) { }
    private record LegacyAllocation(UUID receivableId, BigDecimal amount) { }
}
