package com.nexa.api.creditreceivables.infrastructure.persistence;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Set;
import java.util.UUID;

/** Immutable financial correction, receivable effect and obligation projection. */
@Repository
@Profile("!test")
public class JdbcFinancialAdjustmentAdapter implements FinancialAdjustmentCommands {
    private final JdbcTemplate jdbc;
    private final BusinessTraceabilityCommands traceability;
    private final PaymentConfirmationQuery paymentConfirmations;
    private final SalesOrderFulfillmentQuery salesOrders;

    @Autowired
    public JdbcFinancialAdjustmentAdapter(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability,
                                          PaymentConfirmationQuery paymentConfirmations,
                                          SalesOrderFulfillmentQuery salesOrders) {
        this.jdbc = jdbc;
        this.traceability = traceability;
        this.paymentConfirmations = paymentConfirmations;
        this.salesOrders = salesOrders;
    }

    public JdbcFinancialAdjustmentAdapter(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability,
                                          PaymentConfirmationQuery paymentConfirmations) {
        this(jdbc, traceability, paymentConfirmations, null);
    }

    public JdbcFinancialAdjustmentAdapter(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability) {
        this(jdbc, traceability, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result postFinalQuantityAdjustment(Request request) {
        if (!"DECREASE".equals(request.effect()) || !("DECREASE".equals(request.adjustmentKind()) || "CORRECTION".equals(request.adjustmentKind()))) {
            throw error("FINAL_ADJUSTMENT_MUST_DECREASE");
        }
        if (!request.sourceType().startsWith("FINAL_")) throw error("FINAL_ADJUSTMENT_SOURCE_INVALID");
        return post(request);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result post(Request request) {
        lock(request);
        ExistingAdjustment existing = existing(request);
        if (existing != null) {
            ensureHash(existing.requestHash(), request.requestHash());
            return loadResult(request.tenantId(), request.workspaceId(), existing.id());
        }
        validateKind(request.adjustmentKind(), request.effect());
        SalesOrderFulfillmentQuery.Snapshot source = lockSource(request);
        ReceivableRow receivable = lockReceivable(request);
        if (!receivable.currency().equalsIgnoreCase(request.currency())) throw error("RECEIVABLE_CURRENCY_MISMATCH");
        if (!"SALES_ORDER".equals(receivable.subjectType()) || request.salesOrderId() == null
                || !request.salesOrderId().equals(receivable.subjectId())) {
            throw error("ADJUSTMENT_SOURCE_INVALID");
        }
        if (!java.util.Set.of("REFUND", "CUSTOMER_CREDIT").contains(request.obligationType())) {
            throw error("REFUND_CREDIT_OBLIGATION_TYPE_INVALID");
        }
        if (request.expectedReceivableVersion() != null && receivable.version() != request.expectedReceivableVersion()) {
            throw error("CONCURRENCY_CONFLICT");
        }
        BigDecimal previousAdjustedAmount = receivable.amount().add(receivable.adjustmentTotal());
        validateSource(request, receivable, previousAdjustedAmount, source);
        BigDecimal delta = "INCREASE".equals(request.effect()) ? request.amount() : request.amount().negate();
        BigDecimal adjustedAmount = previousAdjustedAmount.add(delta);
        if (adjustedAmount.signum() < 0) throw error("ADJUSTMENT_EXCEEDS_RECEIVABLE");
        Instant now = request.now();
        long nextReceivableVersion = receivable.version() + 1;
        BigDecimal outstandingAmount = adjustedAmount.subtract(receivable.amountPaid()).max(BigDecimal.ZERO);
        UUID adjustmentId = UUID.randomUUID();
        jdbc.update("insert into payments.financial_adjustment(id,tenant_id,workspace_id,receivable_id,sales_order_id,delivery_id,adjustment_kind,effect,amount,currency,adjusted_amount,outstanding_amount,receivable_version,reason,source_type,source_id,status,actor_membership_id,created_by_identity_id,idempotency_key,request_hash,posted_at,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'POSTED',?,?,?,?,?,?)",
                adjustmentId, request.tenantId(), request.workspaceId(), receivable.id(), request.salesOrderId(), request.deliveryId(),
                request.adjustmentKind(), request.effect(), request.amount(), request.currency(), adjustedAmount, outstandingAmount, nextReceivableVersion,
                bounded(request.reason()), request.sourceType(), request.sourceId(), request.actorMembershipId(), request.createdByIdentityId(), request.idempotencyKey(), request.requestHash(),
                Timestamp.from(now), Timestamp.from(now));
        String nextStatus = status(receivable.status(), receivable.amountPaid(), adjustedAmount);
        if (jdbc.update("update payments.receivable set adjustment_total=adjustment_total+?,status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                delta, nextStatus, Timestamp.from(now), request.tenantId(), request.workspaceId(), receivable.id(), receivable.version()) != 1) {
            throw error("CONCURRENCY_CONFLICT");
        }
        jdbc.update("insert into payments.financial_ledger_entry(id,tenant_id,workspace_id,financial_adjustment_id,receivable_id,effect,amount,delta_amount,currency,posted_at,created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), adjustmentId, receivable.id(), request.effect(),
                request.amount(), delta, request.currency(), Timestamp.from(now), Timestamp.from(now));
        UUID obligationId = null;
        String obligationType = null;
        BigDecimal previousOverpayment = receivable.amountPaid().subtract(previousAdjustedAmount).max(BigDecimal.ZERO);
        BigDecimal overpayment = receivable.amountPaid().subtract(adjustedAmount).max(BigDecimal.ZERO);
        BigDecimal newObligation = overpayment.subtract(previousOverpayment).max(BigDecimal.ZERO);
        if ("DECREASE".equals(request.effect()) && newObligation.signum() > 0) {
            obligationId = UUID.randomUUID();
            obligationType = request.obligationType();
            jdbc.update("insert into payments.refund_credit_obligation(id,tenant_id,workspace_id,financial_adjustment_id,receivable_id,sales_order_id,payment_id,obligation_type,amount,currency,status,reason,actor_membership_id,created_at) values (?,?,?,?,?,?,null,?,?,?,'OPEN',?,?,?)",
                    obligationId, request.tenantId(), request.workspaceId(), adjustmentId, receivable.id(), request.salesOrderId(),
                    obligationType, newObligation, request.currency(), bounded("Refund/credit obligation created from financial adjustment"),
                    request.actorMembershipId(), Timestamp.from(now));
        }
        traceability.record(new BusinessTraceabilityCommands.TraceRequest(request.tenantId(), request.workspaceId(),
                request.actorMembershipId(), "CREDIT_RECEIVABLES", "FINANCIAL_ADJUSTMENT_POSTED", "FinancialAdjustment", adjustmentId,
                request.idempotencyKey(), "financial-adjustment-" + adjustmentId,
                Map.of("receivableId", receivable.id(), "sourceType", request.sourceType(), "sourceId", request.sourceId(),
                        "effect", request.effect(), "amount", request.amount(), "adjustedAmount", adjustedAmount), now));
        if (obligationId != null) {
            traceability.record(new BusinessTraceabilityCommands.TraceRequest(request.tenantId(), request.workspaceId(),
                    request.actorMembershipId(), "CREDIT_RECEIVABLES", "REFUND_CREDIT_OBLIGATION_CREATED", "RefundCreditObligation", obligationId,
                    request.idempotencyKey(), "refund-obligation-" + obligationId,
                    Map.of("financialAdjustmentId", adjustmentId, "amount", newObligation, "obligationType", obligationType), now));
        }
        return new Result(adjustmentId, receivable.id(), delta, adjustedAmount, outstandingAmount, obligationId,
                obligationType, nextReceivableVersion);
    }

    private Result loadResult(UUID tenantId, UUID workspaceId, UUID adjustmentId) {
        return jdbc.query("select a.id,a.receivable_id,a.effect,a.amount,a.adjusted_amount,a.outstanding_amount,a.receivable_version,o.id,o.obligation_type from payments.financial_adjustment a left join payments.refund_credit_obligation o on o.tenant_id=a.tenant_id and o.workspace_id=a.workspace_id and o.financial_adjustment_id=a.id where a.tenant_id=? and a.workspace_id=? and a.id=?",
                (rs, row) -> {
                    BigDecimal delta = "INCREASE".equals(rs.getString(3)) ? rs.getBigDecimal(4) : rs.getBigDecimal(4).negate();
                    return new Result(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), delta, rs.getBigDecimal(5),
                            rs.getBigDecimal(6), rs.getObject(8, UUID.class), rs.getString(9), rs.getLong(7));
                }, tenantId, workspaceId, adjustmentId).stream().findFirst().orElseThrow(() -> error("ADJUSTMENT_NOT_FOUND"));
    }

    private ExistingAdjustment existing(Request request) {
        return jdbc.query("select id,request_hash from payments.financial_adjustment where tenant_id=? and workspace_id=? and actor_membership_id=? and idempotency_key=?",
                (rs, row) -> new ExistingAdjustment(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey()).stream().findFirst().orElse(null);
    }

    private ReceivableRow lockReceivable(Request request) {
        boolean bySalesOrder = request.receivableId() == null;
        String sql = bySalesOrder
                ? "select id,subject_type,subject_id,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,currency,version from payments.receivable where tenant_id=? and workspace_id=? and subject_type='SALES_ORDER' and subject_id=? for update"
                : "select id,subject_type,subject_id,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,currency,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update";
        UUID reference = bySalesOrder ? request.salesOrderId() : request.receivableId();
        if (reference == null) throw error("RECEIVABLE_REFERENCE_REQUIRED");
        return jdbc.query(sql, (rs, row) -> new ReceivableRow(rs.getObject("id", UUID.class), rs.getString("subject_type"),
                        rs.getObject("subject_id", UUID.class), rs.getBigDecimal("amount"), rs.getBigDecimal("amount_paid"),
                        rs.getBigDecimal("adjustment_total"), rs.getString("status"), rs.getString("currency"), rs.getLong("version")),
                request.tenantId(), request.workspaceId(), reference)
                .stream().findFirst().orElseThrow(() -> error("RECEIVABLE_NOT_FOUND"));
    }

    private void lock(Request request) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                request.tenantId() + "|" + request.workspaceId() + "|financial-adjustment|" + request.actorMembershipId() + "|" + request.idempotencyKey());
    }

    private SalesOrderFulfillmentQuery.Snapshot lockSource(Request request) {
        if (!Set.of("SALES_ORDER_CANCELLATION", "SALES_ORDER_REDUCTION").contains(request.sourceType())) return null;
        if (request.salesOrderId() == null || salesOrders == null) throw error("ADJUSTMENT_SOURCE_INVALID");
        return salesOrders.getForUpdate(request.tenantId(), request.workspaceId(), request.salesOrderId());
    }

    private void validateSource(Request request, ReceivableRow receivable, BigDecimal previousAdjustedAmount,
                                 SalesOrderFulfillmentQuery.Snapshot source) {
        if (!Set.of("SALES_ORDER_CANCELLATION", "SALES_ORDER_REDUCTION").contains(request.sourceType())) return;
        if (source == null || source.currency() == null || !source.currency().equalsIgnoreCase(request.currency())) {
            throw error("RECEIVABLE_CURRENCY_MISMATCH");
        }
        if (paymentConfirmations == null || !paymentConfirmations.hasSuccessfulPayment(
                request.tenantId(), request.workspaceId(), request.salesOrderId())) {
            throw error("PAYMENT_REQUIRED");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from payments.financial_adjustment "
                        + "where tenant_id=? and workspace_id=? and source_type=? and source_id=? and status='POSTED')",
                Boolean.class, request.tenantId(), request.workspaceId(), request.sourceType(), request.sourceId()))) {
            throw error("ADJUSTMENT_ALREADY_POSTED");
        }
        if ("SALES_ORDER_CANCELLATION".equals(request.sourceType())) {
            if (!"CANCELLED".equals(source.status())) throw error("ADJUSTMENT_SOURCE_STATUS_INVALID");
            if (request.amount().compareTo(previousAdjustedAmount) != 0) throw error("ADJUSTMENT_AMOUNT_INVALID");
        } else {
            if (!Set.of("PARTIALLY_FULFILLED", "PARTIALLY_DELIVERED").contains(source.status())) {
                throw error("ADJUSTMENT_SOURCE_STATUS_INVALID");
            }
            if (request.amount().compareTo(previousAdjustedAmount) > 0) throw error("ADJUSTMENT_AMOUNT_INVALID");
        }
        if (source.total() != null && request.amount().compareTo(source.total()) > 0) {
            throw error("ADJUSTMENT_AMOUNT_INVALID");
        }
    }

    private static void validateKind(String kind, String effect) {
        if (!java.util.Set.of("INCREASE", "DECREASE", "WRITE_OFF", "CORRECTION").contains(kind)
                || !java.util.Set.of("INCREASE", "DECREASE").contains(effect)) throw error("ADJUSTMENT_KIND_INVALID");
        if ("INCREASE".equals(kind) && !"INCREASE".equals(effect)) throw error("ADJUSTMENT_EFFECT_INVALID");
        if ("WRITE_OFF".equals(kind) && !"DECREASE".equals(effect)) throw error("ADJUSTMENT_EFFECT_INVALID");
        if ("DECREASE".equals(kind) && !"DECREASE".equals(effect)) throw error("ADJUSTMENT_EFFECT_INVALID");
    }

    private static String status(String current, BigDecimal paid, BigDecimal adjusted) {
        if (paid.compareTo(adjusted) >= 0) return "PAID";
        if (paid.signum() > 0) return "PARTIALLY_PAID";
        return "OVERDUE".equals(current) ? "OVERDUE" : "OPEN";
    }

    private static void ensureHash(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }

    private static String bounded(String value) {
        String trimmed = value == null ? "Financial adjustment" : value.trim();
        if (trimmed.isBlank()) throw error("ADJUSTMENT_REASON_REQUIRED");
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000);
    }

    private static CreditReceivableOperationException error(String code) { return new CreditReceivableOperationException(code); }

    private record ExistingAdjustment(UUID id, String requestHash) { }
    private record ReceivableRow(UUID id, String subjectType, UUID subjectId, BigDecimal amount, BigDecimal amountPaid, BigDecimal adjustmentTotal,
                                 String status, String currency, long version) { }
}
