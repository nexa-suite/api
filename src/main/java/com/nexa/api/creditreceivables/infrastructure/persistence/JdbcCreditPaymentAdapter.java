package com.nexa.api.creditreceivables.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.CreditPaymentCommands;
import com.nexa.api.creditreceivables.domain.model.credit.CreditAccount;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

/** Credit-line settlement implementation kept inside BC-07. */
@Repository
@Profile("!test")
public final class JdbcCreditPaymentAdapter implements CreditPaymentCommands {
    private final JdbcTemplate jdbc;
    private final BusinessTraceabilityCommands traceability;

    public JdbcCreditPaymentAdapter(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability) {
        this.jdbc = jdbc;
        this.traceability = traceability;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(ResultRequest request) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                request.tenantId() + "|" + request.workspaceId() + "|credit-payment|" + request.clientAccountId() + "|" + request.currency());
        CreditRow credit = jdbc.query("select id,credit_limit,credit_exposure,reserved_exposure from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=? and status='ACTIVE' for update",
                (rs, row) -> new CreditRow(rs.getObject("id", UUID.class), rs.getBigDecimal("credit_limit"), rs.getBigDecimal("credit_exposure"), rs.getBigDecimal("reserved_exposure")),
                request.tenantId(), request.workspaceId(), request.clientAccountId(), request.currency()).stream().findFirst()
                .orElseThrow(() -> error("CREDIT_ACCOUNT_NOT_FOUND"));
        CreditReservationRow existing = jdbc.query("select id,amount,status from payments.credit_reservation where tenant_id=? and workspace_id=? and credit_account_id=? and idempotency_key=? for update",
                (rs, row) -> new CreditReservationRow(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getString("status")),
                request.tenantId(), request.workspaceId(), credit.id(), request.idempotencyKey()).stream().findFirst().orElse(null);
        if (existing != null) {
            if (existing.amount().compareTo(request.amount()) != 0) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
            return new Result(credit.id(), existing.id(), credit.exposure(), existing.status());
        }
        CreditAccount aggregate = CreditAccount.rehydrate(credit.id().toString(), credit.limit(), credit.exposure(), credit.reserved());
        aggregate.reserve(request.amount());
        UUID reservationId = UUID.randomUUID();
        int inserted = jdbc.update("insert into payments.credit_reservation(id,tenant_id,workspace_id,credit_account_id,receivable_id,payment_id,amount,status,idempotency_key,created_at) values (?,?,?,?,?,?,?,'RESERVED',?,?)",
                reservationId, request.tenantId(), request.workspaceId(), credit.id(), request.receivableId(), request.paymentId(),
                request.amount(), request.idempotencyKey(), Timestamp.from(request.now()));
        if (inserted != 1) throw error("DATA_INTEGRITY_CONFLICT");
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure+?,version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and credit_exposure+reserved_exposure+?<=credit_limit",
                request.amount(), Timestamp.from(request.now()), request.tenantId(), request.workspaceId(), credit.id(), request.amount()) != 1) {
            throw error("INSUFFICIENT_CREDIT");
        }
        aggregate.consumeReservation(request.amount());
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure-?,credit_exposure=credit_exposure+?,version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and reserved_exposure>=?",
                request.amount(), request.amount(), Timestamp.from(request.now()), request.tenantId(), request.workspaceId(), credit.id(), request.amount()) != 1) {
            throw error("DATA_INTEGRITY_CONFLICT");
        }
        jdbc.update("update payments.credit_reservation set status='CONSUMED' where tenant_id=? and workspace_id=? and id=? and status='RESERVED'",
                request.tenantId(), request.workspaceId(), reservationId);
        traceability.record(new BusinessTraceabilityCommands.TraceRequest(request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), "PAYMENTS", "CREDIT_LINE_PAYMENT_APPLIED", "CreditAccount", credit.id(),
                request.idempotencyKey(), "credit-payment-" + request.paymentId(),
                Map.of("paymentId", request.paymentId(), "receivableId", request.receivableId(), "amount", request.amount()), request.now()));
        return new Result(credit.id(), reservationId, aggregate.exposure(), "CONSUMED");
    }

    private static CreditReceivableOperationException error(String code) {
        return new CreditReceivableOperationException(code);
    }

    private record CreditRow(UUID id, BigDecimal limit, BigDecimal exposure, BigDecimal reserved) { }
    private record CreditReservationRow(UUID id, BigDecimal amount, String status) { }
}
