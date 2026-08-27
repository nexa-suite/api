package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.exception.PaymentOperationInProgressException;
import com.nexa.api.payments.application.exception.PaymentIdempotencyPayloadConflictException;
import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.port.PaymentPersistencePort;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.creditreceivables.application.publicapi.CreditPaymentCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableApplicationCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableCommands;
import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentCommands;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.payments.domain.model.payment.Payment;
import com.nexa.api.payments.domain.model.payment.PaymentMethod;
import com.nexa.api.payments.domain.model.payment.PaymentStatus;
import com.nexa.api.shared.infrastructure.security.RlsRequestScope;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.shared.infrastructure.observability.TechnicalMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Persistence adapter for payment use cases. Amounts, scope and final status stay server/webhook authoritative. */
@Profile("!test")
@Component
public class PaymentService implements PaymentPersistencePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentService.class);
    private static final int MAX_RECONCILIATION_ATTEMPTS = 10;
    private final JdbcTemplate jdbc;
    private final StripePaymentProvider stripe;
    private final String publishableKey;
    private final String webhookSecret;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TechnicalMetrics metrics;
    private final ReceivableApplicationCommands receivableApplications;
    private final CreditPaymentCommands creditPayments;
    private final ReceivableCommands receivables;
    private final BusinessDocumentCommands documents;
    private final BusinessEvidenceQuery businessEvidence;
    private final CustomerAccountQuery customerAccounts;
    private final SalesOrderFulfillmentQuery salesOrders;
    private static final int MAX_DATABASE_TRANSACTION_ATTEMPTS = 3;

    public PaymentService(JdbcTemplate jdbc, StripePaymentProvider stripe,
                          @Value("${nexa.payments.publishable-key:}") String publishableKey,
                          @Value("${nexa.payments.webhook-secret:}") String webhookSecret,
                          PlatformTransactionManager transactionManager,
                          ObjectProvider<TechnicalMetrics> metrics,
                          ReceivableApplicationCommands receivableApplications,
                          CreditPaymentCommands creditPayments,
                          ReceivableCommands receivables,
                          BusinessDocumentCommands documents,
                          BusinessEvidenceQuery businessEvidence,
                          CustomerAccountQuery customerAccounts,
                          SalesOrderFulfillmentQuery salesOrders) {
        this.jdbc = jdbc; this.stripe = stripe; this.publishableKey = publishableKey == null ? "" : publishableKey;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics == null ? null : metrics.getIfAvailable();
        this.receivableApplications = receivableApplications;
        this.creditPayments = creditPayments;
        this.receivables = receivables;
        this.documents = documents;
        this.businessEvidence = businessEvidence;
        this.customerAccounts = customerAccounts;
        this.salesOrders = salesOrders;
        registerInboxGauges();
    }

    @Transactional(readOnly = true)
    public PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(CurrentAccessContext context, int page, int size) {
        context.requirePermission(PermissionKey.PAYMENT_READ);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = safePage * safeSize;
        List<ReceivableRow> rows;
        Long total;
        if (context.hasRole(MembershipRole.BUYER)) {
            String buyerAccountId = customerAccounts == null ? null
                    : customerAccounts.findActiveBuyerDetails(tenant(context).toString(), workspace(context).toString(),
                    context.membershipId().value().toString()).map(value -> value.id()).orElse(null);
            if (buyerAccountId == null) {
                rows = List.of();
                total = 0L;
            } else {
                rows = jdbc.query("select r.id,r.client_account_id,r.subject_type,r.subject_id,r.receivable_number,r.currency,r.amount,r.amount_paid,coalesce(r.adjustment_total,0) adjustment_total,r.status,r.due_at,r.version from payments.receivable r where r.tenant_id=? and r.workspace_id=? and r.client_account_id=? order by r.due_at nulls last,r.created_at desc limit ? offset ?", (rs, n) -> receivableRow(rs), tenant(context), workspace(context), UUID.fromString(buyerAccountId), safeSize, offset);
                total = jdbc.queryForObject("select count(*) from payments.receivable r where r.tenant_id=? and r.workspace_id=? and r.client_account_id=?", Long.class, tenant(context), workspace(context), UUID.fromString(buyerAccountId));
            }
        } else {
            rows = jdbc.query("select r.id,r.client_account_id,r.subject_type,r.subject_id,r.receivable_number,r.currency,r.amount,r.amount_paid,coalesce(r.adjustment_total,0) adjustment_total,r.status,r.due_at,r.version from payments.receivable r where r.tenant_id=? and r.workspace_id=? order by r.due_at nulls last,r.created_at desc limit ? offset ?", (rs, n) -> receivableRow(rs), tenant(context), workspace(context), safeSize, offset);
            total = jdbc.queryForObject("select count(*) from payments.receivable r where r.tenant_id=? and r.workspace_id=?", Long.class, tenant(context), workspace(context));
        }
        List<PaymentModels.ReceivableView> visible = rows.stream().filter(row -> authorizedClient(context, row.clientAccountId())).map(this::receivableView).toList();
        return new PaymentModels.Page<>(visible, safePage, safeSize, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPayments(CurrentAccessContext context, int page, int size, String method, String status) {
        context.requirePermission(PermissionKey.PAYMENT_RECONCILE);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String methodFilter = normalizePaymentMethod(method);
        String statusFilter = normalizePaymentStatus(status);
        StringBuilder where = new StringBuilder(" where p.tenant_id=? and p.workspace_id=?");
        List<Object> parameters = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (methodFilter != null) {
            where.append(" and p.method=?");
            parameters.add(methodFilter);
        }
        if (statusFilter != null) {
            where.append(" and p.status=?");
            parameters.add(statusFilter);
        }
        Long total = jdbc.queryForObject("select count(*) from payments.payment p" + where, Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(safeSize);
        queryParameters.add(safePage * safeSize);
        List<PaymentModels.PaymentSummaryView> values = jdbc.query(
                "select p.id,p.receivable_id,r.receivable_number,p.client_account_id,p.method,p.status,p.amount,p.currency,p.bank_transfer_reference,p.review_reason,p.created_at,p.completed_at from payments.payment p join payments.receivable r on r.tenant_id=p.tenant_id and r.workspace_id=p.workspace_id and r.id=p.receivable_id" + where + " order by p.created_at desc,p.id desc limit ? offset ?",
                (rs, n) -> new PaymentModels.PaymentSummaryView(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getObject("receivable_id", UUID.class).toString(),
                        rs.getString("receivable_number"),
                        rs.getObject("client_account_id", UUID.class).toString(),
                        rs.getString("method"),
                        rs.getString("status"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("bank_transfer_reference"),
                        rs.getString("review_reason"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()),
                queryParameters.toArray());
        return new PaymentModels.Page<>(values, safePage, safeSize, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPaymentsForReceivable(CurrentAccessContext context, UUID receivableId, int page, int size) {
        context.requirePermission(PermissionKey.PAYMENT_READ);
        ReceivableRow receivable = receivableQuery(context, receivableId).stream()
                .filter(row -> authorizedClient(context, row.clientAccountId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Receivable not found"));
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Long total = jdbc.queryForObject("select count(*) from payments.payment where tenant_id=? and workspace_id=? and receivable_id=?",
                Long.class, tenant(context), workspace(context), receivable.id());
        List<PaymentModels.PaymentSummaryView> values = jdbc.query(
                "select p.id,p.receivable_id,r.receivable_number,p.client_account_id,p.method,p.status,p.amount,p.currency,p.bank_transfer_reference,p.review_reason,p.created_at,p.completed_at "
                        + "from payments.payment p join payments.receivable r on r.tenant_id=p.tenant_id and r.workspace_id=p.workspace_id and r.id=p.receivable_id "
                        + "where p.tenant_id=? and p.workspace_id=? and p.receivable_id=? order by p.created_at desc,p.id desc limit ? offset ?",
                (rs, n) -> new PaymentModels.PaymentSummaryView(rs.getObject("id", UUID.class).toString(),
                        rs.getObject("receivable_id", UUID.class).toString(), rs.getString("receivable_number"),
                        rs.getObject("client_account_id", UUID.class).toString(), rs.getString("method"), rs.getString("status"),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("bank_transfer_reference"),
                        rs.getString("review_reason"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()),
                tenant(context), workspace(context), receivable.id(), safeSize, safePage * safeSize);
        return new PaymentModels.Page<>(values, safePage, safeSize, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public PaymentModels.Page<PaymentModels.ReconciliationCaseView> listReconciliationCases(CurrentAccessContext context, int page, int size, String state) {
        context.requirePermission(PermissionKey.PAYMENT_RECONCILE);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String normalizedState = normalizeReconciliationState(state);
        String where = " where c.tenant_id=? and c.workspace_id=?";
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (normalizedState != null) { where += " and c.state=?"; args.add(normalizedState); }
        Long total = jdbc.queryForObject("select count(*) from payments.payment_reconciliation_case c" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(safePage * safeSize);
        List<PaymentModels.ReconciliationCaseView> values = jdbc.query(
                reconciliationCaseSql() + where + " order by c.updated_at desc,c.id desc limit ? offset ?",
                (rs, n) -> reconciliationCaseView(rs), pageArgs.toArray());
        return new PaymentModels.Page<>(values, safePage, safeSize, total == null ? 0 : total);
    }

    public PaymentModels.ReconciliationCaseView retryReconciliationCase(CurrentAccessContext context, UUID caseId,
                                                                          String operatorNote, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_RECONCILE);
        requireKey(idempotencyKey);
        String normalizedNote = truncate(operatorNote);
        String requestHash = reconciliationRetryHash(context, caseId, normalizedNote);
        ReconciliationRetryClaim claim = transactionTemplate.execute(status ->
                prepareReconciliationRefund(context, caseId, normalizedNote, idempotencyKey, requestHash));
        if (claim.storedResult() != null) {
            if (claim.failureKind() != null) throw replayTechnicalFailure(claim.failureKind());
            return claim.storedResult();
        }
        if (claim.work() == null) return reconciliationCase(context, caseId);
        executeReconciliationRefund(claim.work(), true);
        return retryResultOrCurrent(context, caseId, idempotencyKey, requestHash);
    }

    private ReconciliationRetryClaim prepareReconciliationRefund(CurrentAccessContext context, UUID caseId,
                                                                  String operatorNote, String idempotencyKey, String requestHash) {
        lockIdempotencyKey(context, "reconciliation:" + caseId + ":" + idempotencyKey);
        ReconciliationCaseRow row = jdbc.query(reconciliationCaseSql() + " where c.tenant_id=? and c.workspace_id=? and c.id=? for update",
                (rs, n) -> reconciliationCaseRow(rs), tenant(context), workspace(context), caseId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Reconciliation case not found"));
        RetryIdempotencyRow prior = jdbc.query("select request_hash,result_status,result_json,failure_kind from payments.reconciliation_refund_idempotency where tenant_id=? and workspace_id=? and case_id=? and actor_membership_id=? and idempotency_key=? for update",
                (rs, n) -> new RetryIdempotencyRow(rs.getString("request_hash"), rs.getString("result_status"), rs.getString("result_json"), rs.getString("failure_kind")),
                tenant(context), workspace(context), caseId, context.membershipId().value(), idempotencyKey)
                .stream().findFirst().orElse(null);
        if (prior != null) {
            if (!requestHash.equalsIgnoreCase(prior.requestHash())) throw new PaymentIdempotencyPayloadConflictException();
            if (prior.resultJson() != null) {
                return new ReconciliationRetryClaim(null, decodeRetryResult(prior.resultJson()), prior.failureKind());
            }
            if ("REFUND_PROCESSING".equals(row.state()) && row.leaseUntil() != null && row.leaseUntil().isAfter(Instant.now())) {
                return new ReconciliationRetryClaim(null, null, null);
            }
            jdbc.update("delete from payments.reconciliation_refund_idempotency where tenant_id=? and workspace_id=? and case_id=? and actor_membership_id=? and idempotency_key=? and result_json is null",
                    tenant(context), workspace(context), caseId, context.membershipId().value(), idempotencyKey);
        }
        if ("REFUNDED".equals(row.state()) || "RESOLVED".equals(row.state())) return new ReconciliationRetryClaim(null, null, null);
        if (row.attemptCount() >= MAX_RECONCILIATION_ATTEMPTS) {
            throw new IllegalArgumentException("Refund retry limit reached; operator override is required");
        }
        jdbc.update("insert into payments.reconciliation_refund_idempotency(tenant_id,workspace_id,case_id,actor_membership_id,idempotency_key,request_hash,created_at) values (?,?,?,?,?,?,current_timestamp)",
                tenant(context), workspace(context), caseId, context.membershipId().value(), idempotencyKey, requestHash);
        PaymentRow payment = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=? for update",
                (rs, n) -> paymentRow(rs), tenant(context), workspace(context), row.paymentId())
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Reconciliation payment not found"));
        UUID claimToken = UUID.randomUUID();
        int claimed = jdbc.update("update payments.payment_reconciliation_case set state='REFUND_PROCESSING',attempt_count=?,operator_note=?,last_error=null,lease_until=current_timestamp + interval '10 minutes',claim_token=?,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and (state in ('RECONCILIATION_REQUIRED','REFUND_PENDING','REFUND_FAILED') or (state='REFUND_PROCESSING' and (lease_until is null or lease_until <= current_timestamp))) and attempt_count < ?",
                row.attemptCount() + 1, operatorNote, claimToken, tenant(context), workspace(context), caseId, MAX_RECONCILIATION_ATTEMPTS);
        if (claimed != 1) return new ReconciliationRetryClaim(null, null, null);
        return new ReconciliationRetryClaim(new ReconciliationRefundWork(caseId, tenant(context), workspace(context), payment.id(), payment.providerId(),
                payment.amount(), payment.currency(), claimToken, context.membershipId().value(), idempotencyKey, requestHash), null, null);
    }

    /** Provider I/O is outside the database transaction; the stable case key makes retries safe. */
    private void executeReconciliationRefund(ReconciliationRefundWork work, boolean propagateFailure) {
        StripePaymentProvider.Refund refund;
        try {
            if (work.providerId() == null || work.providerId().isBlank()) {
                throw new IllegalStateException("Captured payment has no provider reference");
            }
            refund = stripe.refundPayment(work.providerId(), minor(work.amount(), work.currency()),
                    work.currency(), reconciliationRefundKey(work.caseId()));
        } catch (RuntimeException exception) {
            handleReconciliationFailure(work, technicalRefundFailure(exception), propagateFailure);
            return;
        }
        if (refund == null || refund.providerRefundId() == null || refund.providerRefundId().isBlank()
                || refund.status() == null || refund.status().isBlank()) {
            handleReconciliationFailure(work, technicalRefundFailure(new IllegalStateException("Payment provider returned an incomplete refund")), propagateFailure);
            return;
        }
        String providerStatus = refund.status().trim().toLowerCase(Locale.ROOT);
        if (!"succeeded".equals(providerStatus)) {
            boolean pending = "pending".equals(providerStatus);
            TechnicalFailureException failure = pending ? null : new TechnicalFailureException(
                    TechnicalFailureException.Kind.EXTERNAL_TEMPORARY_FAILURE,
                    "Stripe refund did not succeed: " + providerStatus);
            try {
                transactionTemplate.executeWithoutResult(status -> recordProviderRefundOutcome(work, refund, pending));
            } catch (ReconciliationClaimLostException ignored) {
                LOGGER.warn("Payment reconciliation claim lost before provider outcome was recorded caseId={}", work.caseId());
                return;
            }
            if (failure != null && propagateFailure) throw failure;
            if (failure != null) LOGGER.warn("Payment reconciliation refund failed caseId={} providerStatus={}", work.caseId(), providerStatus);
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> finalizeReconciliationRefund(work, refund.providerRefundId()));
        } catch (ReconciliationClaimLostException ignored) {
            LOGGER.warn("Payment reconciliation claim lost after provider success caseId={}", work.caseId());
        } catch (RuntimeException exception) {
            handleReconciliationFailure(work, technicalRefundFailure(exception), propagateFailure);
        }
    }

    private void finalizeReconciliationRefund(ReconciliationRefundWork work, String providerRefundId) {
        int finalized = jdbc.update("update payments.payment_reconciliation_case set state='REFUNDED',provider_refund_id=?,lease_until=null,claim_token=null,updated_at=current_timestamp,resolved_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and state='REFUND_PROCESSING' and claim_token=? and lease_until > current_timestamp",
                providerRefundId, work.tenantId(), work.workspaceId(), work.caseId(), work.claimToken());
        if (finalized != 1) throw new ReconciliationClaimLostException();
        jdbc.update("update payments.payment set status='REFUNDED',updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='SUCCEEDED'",
                work.tenantId(), work.workspaceId(), work.paymentId());
        saveRetryResult(work, reconciliationCase(work.tenantId(), work.workspaceId(), work.caseId()), "SUCCESS", null);
    }

    private void recordProviderRefundOutcome(ReconciliationRefundWork work, StripePaymentProvider.Refund refund, boolean pending) {
        String state = pending ? "REFUND_PENDING" : "REFUND_FAILED";
        String detail = pending ? "Provider refund is pending" : "Provider refund status: " + refund.status();
        int updated = jdbc.update("update payments.payment_reconciliation_case set state=?,provider_refund_id=?,last_error=?,lease_until=null,claim_token=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and state='REFUND_PROCESSING' and claim_token=? and lease_until > current_timestamp",
                state, refund.providerRefundId(), detail, work.tenantId(), work.workspaceId(), work.caseId(), work.claimToken());
        if (updated != 1) throw new ReconciliationClaimLostException();
        saveRetryResult(work, reconciliationCase(work.tenantId(), work.workspaceId(), work.caseId()), pending ? "SUCCESS" : "FAILURE",
                pending ? null : TechnicalFailureException.Kind.EXTERNAL_TEMPORARY_FAILURE);
    }

    private void handleReconciliationFailure(ReconciliationRefundWork work, TechnicalFailureException failure, boolean propagateFailure) {
        try {
            transactionTemplate.executeWithoutResult(status -> failReconciliationRefund(work, failure));
        } catch (ReconciliationClaimLostException ignored) {
            LOGGER.warn("Payment reconciliation claim lost while recording failure caseId={}", work.caseId());
            return;
        }
        if (propagateFailure) throw failure;
        LOGGER.warn("Payment reconciliation refund failed caseId={}", work.caseId(), failure);
    }

    private void failReconciliationRefund(ReconciliationRefundWork work, TechnicalFailureException exception) {
        int failed = jdbc.update("update payments.payment_reconciliation_case set state='REFUND_FAILED',last_error=?,lease_until=null,claim_token=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and state='REFUND_PROCESSING' and claim_token=? and lease_until > current_timestamp",
                truncate(exception.getMessage()), work.tenantId(), work.workspaceId(), work.caseId(), work.claimToken());
        if (failed != 1) throw new ReconciliationClaimLostException();
        saveRetryResult(work, reconciliationCase(work.tenantId(), work.workspaceId(), work.caseId()), "FAILURE", exception.kind());
    }

    private void saveRetryResult(ReconciliationRefundWork work, PaymentModels.ReconciliationCaseView result,
                                 String resultStatus, TechnicalFailureException.Kind failureKind) {
        if (work.idempotencyKey() == null) return;
        jdbc.update("update payments.reconciliation_refund_idempotency set result_status=?,result_json=?::jsonb,failure_kind=?,completed_at=current_timestamp where tenant_id=? and workspace_id=? and case_id=? and actor_membership_id=? and idempotency_key=? and request_hash=? and result_json is null",
                resultStatus, json(result), failureKind == null ? null : failureKind.name(), work.tenantId(), work.workspaceId(), work.caseId(),
                work.actorMembershipId(), work.idempotencyKey(), work.requestHash());
    }

    private PaymentModels.ReconciliationCaseView retryResultOrCurrent(CurrentAccessContext context, UUID caseId,
                                                                       String idempotencyKey, String requestHash) {
        RetryIdempotencyRow result = jdbc.query("select result_status,result_json,failure_kind,request_hash from payments.reconciliation_refund_idempotency where tenant_id=? and workspace_id=? and case_id=? and actor_membership_id=? and idempotency_key=?",
                (rs, n) -> new RetryIdempotencyRow(rs.getString("request_hash"), rs.getString("result_status"), rs.getString("result_json"), rs.getString("failure_kind")),
                tenant(context), workspace(context), caseId, context.membershipId().value(), idempotencyKey).stream().findFirst().orElse(null);
        if (result != null && !requestHash.equalsIgnoreCase(result.requestHash())) throw new PaymentIdempotencyPayloadConflictException();
        if (result != null && result.resultJson() != null) {
            if (result.failureKind() != null) throw replayTechnicalFailure(result.failureKind());
            return decodeRetryResult(result.resultJson());
        }
        return reconciliationCase(context, caseId);
    }

    private TechnicalFailureException technicalRefundFailure(RuntimeException exception) {
        return exception instanceof TechnicalFailureException technical ? technical
                : new TechnicalFailureException(TechnicalFailureException.Kind.EXTERNAL_TEMPORARY_FAILURE,
                "Payment provider refund failed", exception);
    }

    private TechnicalFailureException replayTechnicalFailure(String failureKind) {
        TechnicalFailureException.Kind kind;
        try { kind = TechnicalFailureException.Kind.valueOf(failureKind); }
        catch (IllegalArgumentException ignored) { kind = TechnicalFailureException.Kind.EXTERNAL_TEMPORARY_FAILURE; }
        return new TechnicalFailureException(kind, "Payment reconciliation refund previously failed");
    }

    @Transactional(readOnly = true)
    public PaymentModels.ReceivableView getReceivable(CurrentAccessContext context, UUID receivableId) {
        context.requirePermission(PermissionKey.PAYMENT_READ);
        return receivableQuery(context, receivableId).stream().filter(row -> authorizedClient(context, row.clientAccountId())).findFirst()
                .map(this::receivableView).orElseThrow(() -> new IllegalArgumentException("Receivable not found"));
    }

    @Transactional(readOnly = true)
    public PaymentModels.PaymentView getPayment(CurrentAccessContext context, UUID paymentId) {
        context.requirePermission(PermissionKey.PAYMENT_READ);
        return jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id,p.method from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=?", (rs, n) -> new PaymentViewRow(paymentRow(rs), rs.getString("method")), tenant(context), workspace(context), paymentId).stream()
                .filter(row -> authorizedClient(context, row.payment().clientAccountId())).map(row -> paymentView(row.payment(), row.method())).findFirst().orElseThrow(() -> new IllegalArgumentException("Payment not found"));
    }

    public PaymentModels.ReceivableView createReceivable(CurrentAccessContext context, PaymentPersistencePort.ReceivableCommand request) {
        context.requirePermission(PermissionKey.PAYMENT_RECONCILE);
        requireKey(request.idempotencyKey());
        return executeIdempotentDatabaseTransaction("receivable-create", request.idempotencyKey(),
                () -> createReceivableInTransaction(context, request));
    }

    private PaymentModels.ReceivableView createReceivableInTransaction(CurrentAccessContext context,
                                                                         PaymentPersistencePort.ReceivableCommand request) {
        lockIdempotencyKey(context, request.idempotencyKey());
        if (request.subjectId() == null || request.subjectType() == null || request.subjectType().isBlank()) {
            throw new IllegalArgumentException("Receivable subject is required");
        }
        String subjectType = request.subjectType().trim().toUpperCase(Locale.ROOT);
        AuthoritativeSubject subject = authoritativeSubject(context, subjectType, request.subjectId());
        PaymentModels.ReceivableView existing = receivableQuery(context, request.subjectId(), subjectType).stream()
                .filter(row -> authorizedClient(context, row.clientAccountId())).findFirst().map(this::receivableView).orElse(null);
        if (existing != null) {
            ensureCanonicalReceivable(existing, subject);
            return existing;
        }
        if (!"SALES_ORDER".equals(subjectType) || receivables == null) {
            throw new IllegalStateException("Sales Order receivable boundary is not configured");
        }
        UUID id = receivables.postForSalesOrder(tenant(context), workspace(context), request.subjectId(),
                subject.clientAccountId(), subject.amount(), subject.currency(), Instant.now());
        return getReceivable(context, id);
    }

    public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE);
        requireKey(idempotencyKey);
        CardPaymentClaim claim = transactionTemplate.execute(status -> prepareCardPaymentClaim(context, receivableId, idempotencyKey));
        if (claim == null) throw new IllegalStateException("Card payment claim could not be prepared");
        if (claim.payment().providerId() != null) {
            return paymentIntentView(claim.payment(), providerSecret(claim.payment()));
        }

        StripePaymentProvider.PaymentIntent intent;
        try {
            intent = stripe.createPaymentIntent(new StripePaymentProvider.PaymentIntentRequest(
                    claim.amountMinor(), claim.receivable().currency(), claim.providerIdempotencyKey(), claim.metadata()));
            requireProviderIntent(intent);
        } catch (RuntimeException exception) {
            recordProviderFailure(context, claim, exception);
            throw exception;
        }

        PaymentRow payment = transactionTemplate.execute(status -> persistProviderIntent(context, claim, intent));
        if (payment == null) throw new IllegalStateException("Payment provider result could not be persisted");
        return paymentIntentView(payment, intent.clientSecret());
    }

    /**
     * Local-only browser acceptance seam. It exercises the official Stripe
     * adapter against the configured Stripe-compatible provider, then sends a
     * signed event through the same webhook inbox/worker used in production.
     * There is no route for this outside the local profile.
     */
    public PaymentModels.PaymentView confirmTestCardPayment(CurrentAccessContext context, UUID receivableId, String clientSecret) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE);
        if (clientSecret == null || clientSecret.isBlank() || clientSecret.length() > 512) throw new IllegalArgumentException("Stripe client secret is required");
        ConfirmationClaim claim = transactionTemplate.execute(status -> prepareConfirmationClaim(context, receivableId));
        if (claim == null) throw new IllegalStateException("Stripe payment confirmation claim could not be prepared");
        ReceivableRow receivable = claim.receivable();
        PaymentRow payment = claim.payment();
        if (payment == null) throw new IllegalArgumentException("Stripe payment intent was not created");
        if (PaymentStatus.SUCCEEDED.name().equals(payment.status())) return paymentView(payment, PaymentMethod.CARD_STRIPE.name());
        if (!PaymentStatus.REQUIRES_ACTION.name().equals(payment.status()) && !PaymentStatus.PROCESSING.name().equals(payment.status())) {
            throw new IllegalArgumentException("Stripe payment is not confirmable");
        }
        StripePaymentProvider.PaymentIntent current = stripe.retrievePaymentIntent(payment.providerId()).orElseThrow(() -> new IllegalArgumentException("Stripe PaymentIntent was not found"));
        if (current.clientSecret() == null || !MessageDigest.isEqual(current.clientSecret().getBytes(StandardCharsets.UTF_8), clientSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Stripe client secret does not match the payment intent");
        }
        StripePaymentProvider.PaymentIntent confirmed = stripe.confirmPaymentIntent(payment.providerId());
        if (confirmed == null || !"succeeded".equalsIgnoreCase(confirmed.status())) {
            throw new IllegalArgumentException("Stripe test payment did not succeed");
        }
        String payload = testSucceededPayload(confirmed, payment, receivable, context);
        receiveStripeWebhook(payload, signWebhook(payload));
        processStripeWebhookInbox();
        return getPayment(context, payment.id());
    }

    public PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE);
        requireKey(idempotencyKey);
        return executeIdempotentDatabaseTransaction("credit-line-payment", idempotencyKey,
                () -> createCreditLinePaymentInTransaction(context, receivableId, idempotencyKey));
    }

    private PaymentModels.PaymentView createCreditLinePaymentInTransaction(CurrentAccessContext context,
                                                                             UUID receivableId, String idempotencyKey) {
        lockIdempotencyKey(context, idempotencyKey);
        ReceivableRow receivable = lockedReceivable(context, receivableId); ensureBuyerScope(context, receivable.clientAccountId());
        BigDecimal amount = payableAmount(receivable);
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        ExistingPayment existing = existingPayment(context, idempotencyKey);
        if (existing != null) {
            ensureIdempotentPayment(existing, receivable, PaymentMethod.CREDIT_LINE, amount);
            return paymentView(existing.payment(), PaymentMethod.CREDIT_LINE.name());
        }
        UUID paymentId = UUID.randomUUID(); Instant now = Instant.now();
        int inserted = jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,idempotency_key,created_at,updated_at,completed_at) values (?,?,?,?,?,?, 'CREDIT_LINE','SUCCEEDED',?,?, 'NEXA_CREDIT',?,?,?,?) on conflict (tenant_id,workspace_id,created_by_membership_id,idempotency_key) do nothing", paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), amount, receivable.currency(), idempotencyKey, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) throw new IllegalArgumentException("Payment idempotency claim was lost");
        creditPayments.apply(new CreditPaymentCommands.ResultRequest(tenant(context), workspace(context),
                context.membershipId().value(), receivable.clientAccountId(), receivable.id(), paymentId,
                amount, receivable.currency(), idempotencyKey, now));
        applySucceededPayment(context, paymentId, receivable.id(), amount, receivable.currency(), "credit-line-" + paymentId);
        PaymentRow payment = new PaymentRow(paymentId, receivable.id(), PaymentStatus.SUCCEEDED.name(), amount, receivable.currency(), null, null, now, now, receivable.clientAccountId(), tenant(context), workspace(context));
        return paymentView(payment, PaymentMethod.CREDIT_LINE.name());
    }

    public PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId, String idempotencyKey,
                                                        String transferReference, UUID proofEvidenceId) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE);
        requireKey(idempotencyKey);
        return executeIdempotentDatabaseTransaction("bank-transfer-create", idempotencyKey,
                () -> createBankTransferInTransaction(context, receivableId, idempotencyKey, transferReference, proofEvidenceId));
    }

    private PaymentModels.PaymentView createBankTransferInTransaction(CurrentAccessContext context, UUID receivableId,
                                                                       String idempotencyKey, String transferReference,
                                                                       UUID proofEvidenceId) {
        lockIdempotencyKey(context, idempotencyKey);
        if (transferReference == null || transferReference.isBlank() || transferReference.length() > 160) throw new IllegalArgumentException("Bank transfer reference is required");
        ReceivableRow receivable = lockedReceivable(context, receivableId); ensureBuyerScope(context, receivable.clientAccountId()); BigDecimal amount = payableAmount(receivable);
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        ExistingPayment existing = existingPayment(context, idempotencyKey);
        if (existing != null) {
            ensureBankTransferIdempotency(existing, receivable, transferReference, proofEvidenceId);
            return paymentView(existing.payment(), PaymentMethod.BANK_TRANSFER.name());
        }
        validateProofEvidence(context, receivable, proofEvidenceId);
        UUID paymentId = UUID.randomUUID(); Instant now = Instant.now();
        int inserted = jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,idempotency_key,bank_transfer_reference,bank_transfer_proof_evidence_id,created_at,updated_at) values (?,?,?,?,?,?, 'BANK_TRANSFER','PROCESSING',?,?, 'BANK_TRANSFER',?,?,?,?,?) on conflict (tenant_id,workspace_id,created_by_membership_id,idempotency_key) do nothing", paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), amount, receivable.currency(), idempotencyKey, transferReference.trim(), proofEvidenceId, Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) {
            ExistingPayment concurrent = existingPayment(context, idempotencyKey);
            if (concurrent == null) throw new IllegalArgumentException("Payment idempotency claim was lost");
            ensureBankTransferIdempotency(concurrent, receivable, transferReference, proofEvidenceId);
            return paymentView(concurrent.payment(), PaymentMethod.BANK_TRANSFER.name());
        }
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,created_at) values (?,?,?,?,1,'PROCESSING',?)", UUID.randomUUID(), tenant(context), workspace(context), paymentId, Timestamp.from(now));
        return paymentView(new PaymentRow(paymentId, receivable.id(), PaymentStatus.PROCESSING.name(), amount, receivable.currency(), null, null, now, null, receivable.clientAccountId(), tenant(context), workspace(context)), PaymentMethod.BANK_TRANSFER.name());
    }

    public PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext context, UUID paymentId, String action,
                                                        String reason, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_RECONCILE);
        requireKey(idempotencyKey);
        return executeIdempotentDatabaseTransaction("bank-transfer-review", idempotencyKey,
                () -> reviewBankTransferInTransaction(context, paymentId, action, reason, idempotencyKey));
    }

    private PaymentModels.PaymentView reviewBankTransferInTransaction(CurrentAccessContext context, UUID paymentId,
                                                                       String action, String reason, String idempotencyKey) {
        lockIdempotencyKey(context, "bank-review:" + paymentId + ":" + idempotencyKey);
        String normalized = normalizeReviewAction(action);
        String normalizedReason = normalizeReviewReason(normalized, reason);
        PaymentRow payment = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=? and p.method='BANK_TRANSFER' for update", (rs, n) -> paymentRow(rs), tenant(context), workspace(context), paymentId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Bank transfer payment not found"));
        StoredBankReview previousReview = jdbc.query("select review_idempotency_key,review_action,review_reason from payments.payment "
                        + "where tenant_id=? and workspace_id=? and id=? and method='BANK_TRANSFER'",
                (rs, n) -> new StoredBankReview(rs.getString("review_idempotency_key"), rs.getString("review_action"), rs.getString("review_reason")),
                tenant(context), workspace(context), paymentId).stream().findFirst().orElse(null);
        if (previousReview != null && idempotencyKey.equals(previousReview.idempotencyKey())) {
            if (!Objects.equals(normalized, previousReview.action()) || !Objects.equals(normalizedReason, previousReview.reason())) {
                throw new PaymentIdempotencyPayloadConflictException();
            }
            return paymentView(payment, "BANK_TRANSFER");
        }
        if (!Set.of("PROCESSING", "FAILED").contains(payment.status())) throw new IllegalArgumentException("Bank transfer is not reviewable");
        if ("REJECT".equals(normalized)) {
            jdbc.update("update payments.payment set status='FAILED',review_idempotency_key=?,review_action='REJECT',reviewed_by_membership_id=?,reviewed_at=current_timestamp,review_reason=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=?", idempotencyKey, context.membershipId().value(), normalizedReason, tenant(context), workspace(context), payment.id());
            jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,failure_code,failure_detail,created_at) select ?,tenant_id,workspace_id,id,coalesce((select max(attempt_number)+1 from payments.payment_attempt where payment_id=?),1),'FAILED','BANK_TRANSFER_REJECTED',?,current_timestamp from payments.payment where id=?", UUID.randomUUID(), payment.id(), normalizedReason, payment.id());
            return paymentView(new PaymentRow(payment.id(), payment.receivableId(), "FAILED", payment.amount(), payment.currency(), null, payment.providerId(), payment.createdAt(), null, payment.clientAccountId(), payment.tenantId(), payment.workspaceId()), "BANK_TRANSFER");
        }
        validateStoredProofEvidence(context, payment);
        applySucceededPaymentForStoredContext(payment, "bank-transfer-" + idempotencyKey);
        jdbc.update("update payments.payment set status='SUCCEEDED',review_idempotency_key=?,review_action=?,reviewed_by_membership_id=?,reviewed_at=current_timestamp,review_reason=?,updated_at=current_timestamp,completed_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=?", idempotencyKey, normalized, context.membershipId().value(), normalizedReason, tenant(context), workspace(context), payment.id());
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,provider_reference,created_at) select ?,tenant_id,workspace_id,id,coalesce((select max(attempt_number)+1 from payments.payment_attempt where payment_id=?),1),'SUCCEEDED',bank_transfer_reference,current_timestamp from payments.payment where id=?", UUID.randomUUID(), payment.id(), payment.id());
        return paymentView(new PaymentRow(payment.id(), payment.receivableId(), "SUCCEEDED", payment.amount(), payment.currency(), null, payment.providerId(), payment.createdAt(), Instant.now(), payment.clientAccountId(), payment.tenantId(), payment.workspaceId()), "BANK_TRANSFER");
    }

    @Transactional
    public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) {
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("Stripe webhook payload is required");
        StripePaymentProvider.StripeWebhookEvent event = stripe.verifyWebhook(payload, signature);
        requireWebhookIdentity(event);
        UUID eventTenant = metadataUuid(event, "nexa_tenant_id");
        UUID eventWorkspace = metadataUuid(event, "nexa_workspace_id");
        if (event.paymentIntentId() != null && (eventTenant == null || eventWorkspace == null)) {
            throw new IllegalArgumentException("Stripe webhook tenant binding is required");
        }
        String signatureHash = sha256(signature == null ? "" : signature);
        int inserted = jdbc.update("insert into payments.stripe_event_inbox (event_id,event_type,payment_intent_id,payment_status,amount_minor,currency,tenant_id,workspace_id,signature_sha256,received_at) values (?,?,?,?,?,?,?,?,?,?) on conflict (event_id) do nothing", event.eventId(), event.eventType(), event.paymentIntentId(), event.paymentStatus(), event.amountMinor(), event.currency() == null ? null : event.currency().toUpperCase(Locale.ROOT), eventTenant, eventWorkspace, signatureHash, Timestamp.from(Instant.now()));
        return new PaymentModels.WebhookReceipt(event.eventId(), inserted == 0 ? "DUPLICATE" : "ACCEPTED");
    }

    @Scheduled(fixedDelayString = "${nexa.payments.webhook-worker-delay-ms:1000}")
    public void processStripeWebhookInbox() {
        jdbc.update("update payments.stripe_event_inbox set status=case when attempt_count >= 10 then 'DEAD_LETTER' else 'FAILED' end,failure_detail='Stale processing attempt',next_attempt_at=current_timestamp,processing_started_at=null,lease_until=null,claim_token=null where status='PROCESSING' and lease_until <= current_timestamp");
        List<WebhookWork> work = jdbc.query("select event_id,tenant_id,workspace_id from payments.stripe_event_inbox where status in ('RECEIVED','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp order by received_at,event_id limit 20", (rs, n) -> new WebhookWork(rs.getString("event_id"), rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class)));
        for (WebhookWork item : work) {
            UUID claimToken = UUID.randomUUID();
            if (jdbc.update("update payments.stripe_event_inbox set status='PROCESSING',attempt_count=attempt_count+1,failure_detail=null,processing_started_at=current_timestamp,lease_until=current_timestamp + interval '10 minutes',claim_token=? where event_id=? and status in ('RECEIVED','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp", claimToken, item.eventId()) == 0) {
                count("claim", "lost");
                continue;
            }
            count("claim", "acquired");
            TechnicalMetrics.TimerSample timer = start("process");
            try {
                if (item.tenantId() != null && item.workspaceId() != null) RlsRequestScope.set(item.tenantId(), item.workspaceId());
                transactionTemplate.executeWithoutResult(transaction -> {
                    assertInboxClaim(item.eventId(), claimToken);
                    InboxOutcome outcome = processWebhook(item.eventId(), claimToken);
                    int finalized = jdbc.update("update payments.stripe_event_inbox set status=?,processed_at=current_timestamp,next_attempt_at=current_timestamp,processing_started_at=null,lease_until=null,claim_token=null where event_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp", outcome.name(), item.eventId(), claimToken);
                    if (finalized != 1) throw new InboxClaimLostException();
                });
                record(timer, "processed");
                count("process", "success");
            } catch (RuntimeException exception) {
                int deadLettered = jdbc.update("update payments.stripe_event_inbox set status=case when attempt_count >= 10 then 'DEAD_LETTER' else 'FAILED' end,failure_detail=?,next_attempt_at=case when attempt_count >= 10 then current_timestamp else current_timestamp + (least(power(2,attempt_count),300) * interval '1 second') end,processing_started_at=null,lease_until=null,claim_token=null where event_id=? and status='PROCESSING' and claim_token=?", truncate(exception.getMessage()), item.eventId(), claimToken);
                String outcome = deadLettered == 1 && "DEAD_LETTER".equals(jdbc.queryForObject("select status from payments.stripe_event_inbox where event_id=?", String.class, item.eventId())) ? "dead_letter" : "failed";
                record(timer, outcome);
                count("process", outcome);
            } finally {
                RlsRequestScope.clear();
            }
        }
    }

    private void registerInboxGauges() {
        if (metrics == null) return;
        metrics.gauge("inbox", "pending", () -> queryDouble("select count(*) from payments.stripe_event_inbox where status in ('RECEIVED','FAILED')"));
        metrics.gauge("inbox", "oldest_pending_age_seconds", () -> queryDouble("select coalesce(extract(epoch from current_timestamp - min(received_at)),0) from payments.stripe_event_inbox where status in ('RECEIVED','FAILED')"));
        metrics.gauge("inbox", "failed", () -> queryDouble("select count(*) from payments.stripe_event_inbox where status='FAILED'"));
        metrics.gauge("inbox", "processing_age_seconds", () -> queryDouble("select coalesce(extract(epoch from current_timestamp - min(processing_started_at)),0) from payments.stripe_event_inbox where status='PROCESSING'"));
    }

    private double queryDouble(String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private TechnicalMetrics.TimerSample start(String operation) { return metrics == null ? null : metrics.start("inbox", operation); }
    private void record(TechnicalMetrics.TimerSample timer, String outcome) { if (metrics != null && timer != null) timer.stop(outcome); }
    private void count(String operation, String outcome) { if (metrics != null) metrics.count("inbox", operation, outcome); }

    @Transactional
    InboxOutcome processWebhook(String eventId, UUID claimToken) {
        assertInboxClaim(eventId, claimToken);
        StripeEventRow event = jdbc.query("select event_id,event_type,payment_intent_id,payment_status,amount_minor,currency,tenant_id,workspace_id from payments.stripe_event_inbox where event_id=?", (rs, n) -> new StripeEventRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, Long.class), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class)), eventId).stream().findFirst().orElseThrow();
        if (event.paymentIntentId() == null || !event.eventType().startsWith("payment_intent.")) return InboxOutcome.IGNORED;
        if (event.tenantId() == null || event.workspaceId() == null) throw new IllegalArgumentException("Stripe webhook tenant binding is missing");
        PaymentRow payment = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.provider_payment_intent_id=? for update", (rs, n) -> paymentRow(rs), event.tenantId(), event.workspaceId(), event.paymentIntentId()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Stripe payment intent is not known"));
        if (event.amountMinor() != null && event.amountMinor() != minor(payment.amount(), payment.currency())) throw new IllegalArgumentException("Stripe amount does not match receivable payment");
        if (event.currency() != null && !event.currency().equalsIgnoreCase(payment.currency())) throw new IllegalArgumentException("Stripe currency does not match receivable payment");
        PaymentStatus next = statusFromEvent(event.eventType(), event.paymentStatus());
        if (next == null) return InboxOutcome.IGNORED;
        Payment aggregate = Payment.rehydrate(payment.id().toString(), payment.amount(), PaymentStatus.valueOf(payment.status()));
        final boolean changed;
        try {
            changed = aggregate.applyProviderStatus(next);
        } catch (IllegalArgumentException staleEvent) {
            return InboxOutcome.IGNORED;
        }
        if (!changed) {
            return InboxOutcome.IGNORED;
        }
        if (next == PaymentStatus.SUCCEEDED) applySucceededPaymentForStoredContext(payment, eventId);
        assertInboxClaim(eventId, claimToken);
        jdbc.update("update payments.payment set status=?,updated_at=current_timestamp,completed_at=case when ?='SUCCEEDED' then current_timestamp else completed_at end,version=version+1 where tenant_id=? and workspace_id=? and id=?", aggregate.status().name(), aggregate.status().name(), event.tenantId(), event.workspaceId(), payment.id());
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,provider_reference,failure_code,created_at) select ?,p.tenant_id,p.workspace_id,p.id,coalesce((select max(a.attempt_number)+1 from payments.payment_attempt a where a.payment_id=p.id),1),?,?,case when ?='FAILED' then 'PROVIDER_DECLINED' else null end,current_timestamp from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=?", UUID.randomUUID(), aggregate.status().name(), event.paymentIntentId(), aggregate.status().name(), event.tenantId(), event.workspaceId(), payment.id());
        if (next == PaymentStatus.SUCCEEDED) reconcileCapturedPaymentIfSalesOrderMissing(payment, eventId);
        return InboxOutcome.PROCESSED;
    }

    private void assertInboxClaim(String eventId, UUID claimToken) {
        Boolean owner = jdbc.queryForObject("select exists(select 1 from payments.stripe_event_inbox where event_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp)", Boolean.class, eventId, claimToken);
        if (!Boolean.TRUE.equals(owner)) throw new InboxClaimLostException();
    }

    @Scheduled(fixedDelayString = "${nexa.payments.reconciliation-worker-delay-ms:5000}")
    public void processPendingReconciliationCases() {
        UUID lastTenant = null;
        UUID lastWorkspace = null;
        while (true) {
            List<WorkspaceScope> scopes = lastTenant == null
                    ? jdbc.query("select tenant_id,id from tenant_management.workspace order by tenant_id,id limit 100",
                    (rs, n) -> new WorkspaceScope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                    : jdbc.query("select tenant_id,id from tenant_management.workspace where (tenant_id,id) > (?,?) order by tenant_id,id limit 100",
                    (rs, n) -> new WorkspaceScope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), lastTenant, lastWorkspace);
            if (scopes.isEmpty()) break;
            for (WorkspaceScope scope : scopes) {
                RlsRequestScope.set(scope.tenantId(), scope.workspaceId());
                try {
                    jdbc.update("update payments.payment_reconciliation_case set state='REFUND_PENDING',lease_until=null,claim_token=null,last_error=coalesce(last_error,'Stale refund claim recovered'),updated_at=current_timestamp where tenant_id=? and workspace_id=? and state='REFUND_PROCESSING' and (lease_until is null or lease_until <= current_timestamp)",
                            scope.tenantId(), scope.workspaceId());
                    List<ReconciliationCandidate> candidates = jdbc.query("""
                            select c.id,c.payment_id,p.provider_payment_intent_id,p.amount,p.currency,c.attempt_count
                            from payments.payment_reconciliation_case c
                            join payments.payment p on p.tenant_id=c.tenant_id and p.workspace_id=c.workspace_id and p.id=c.payment_id
                            where c.tenant_id=? and c.workspace_id=? and c.state='REFUND_PENDING'
                              and c.attempt_count < ?
                            order by c.updated_at,c.id
                            limit 10
                            """, (rs, n) -> new ReconciliationCandidate(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                            rs.getString("provider_payment_intent_id"), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getInt("attempt_count")),
                            scope.tenantId(), scope.workspaceId(), MAX_RECONCILIATION_ATTEMPTS);
                    for (ReconciliationCandidate candidate : candidates) {
                        UUID claimToken = UUID.randomUUID();
                        int claimed = jdbc.update("update payments.payment_reconciliation_case set state='REFUND_PROCESSING',attempt_count=attempt_count+1,lease_until=current_timestamp + interval '10 minutes',claim_token=?,last_error=null,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and state='REFUND_PENDING' and attempt_count < ? and (lease_until is null or lease_until <= current_timestamp)",
                                claimToken, scope.tenantId(), scope.workspaceId(), candidate.caseId(), MAX_RECONCILIATION_ATTEMPTS);
                        if (claimed != 1) continue;
                        executeReconciliationRefund(new ReconciliationRefundWork(candidate.caseId(), scope.tenantId(), scope.workspaceId(),
                                candidate.paymentId(), candidate.providerId(), candidate.amount(), candidate.currency(), claimToken, null, null, null), false);
                    }
                } catch (RuntimeException exception) {
                    LOGGER.error("Payment reconciliation worker failed tenantId={} workspaceId={}", scope.tenantId(), scope.workspaceId(), exception);
                } finally {
                    RlsRequestScope.clear();
                }
            }
            WorkspaceScope last = scopes.get(scopes.size() - 1);
            lastTenant = last.tenantId();
            lastWorkspace = last.workspaceId();
        }
    }

    private void applySucceededPaymentForStoredContext(PaymentRow payment, String eventKey) {
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update", (rs, n) -> receivableRow(rs), payment.tenantId(), payment.workspaceId(), payment.receivableId()).stream().findFirst().orElseThrow();
        if (!receivable.currency().equalsIgnoreCase(payment.currency())) throw new IllegalArgumentException("Payment currency does not match receivable");
        UUID actorMembershipId = jdbc.queryForObject("select created_by_membership_id from payments.payment where tenant_id=? and workspace_id=? and id=?", UUID.class, payment.tenantId(), payment.workspaceId(), payment.id());
        receivableApplications.apply(new ReceivableApplicationCommands.Request(payment.tenantId(), payment.workspaceId(),
                actorMembershipId, receivable.id(), payment.id(), payment.amount(), payment.currency(), eventKey, Instant.now()));
        jdbc.update("insert into payments.payment_event (id,tenant_id,workspace_id,payment_id,event_type,event_key,occurred_at) values (?,?,?,?,?,?,current_timestamp) on conflict (payment_id,event_key) do nothing", UUID.randomUUID(), tenant(payment), workspace(payment), payment.id(), "PAYMENT_SUCCEEDED", eventKey);
        enqueuePaymentReceipt(payment, receivable, eventKey);
        outbox(payment, "PAYMENT_SUCCEEDED", Map.of("paymentId", payment.id(), "receivableId", receivable.id(), "amount", payment.amount(), "currency", payment.currency()));
    }

    private void reconcileCapturedPaymentIfSalesOrderMissing(PaymentRow payment, String eventKey) {
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=?",
                (rs, n) -> receivableRow(rs), payment.tenantId(), payment.workspaceId(), payment.receivableId()).stream().findFirst().orElse(null);
        if (receivable == null || !"SALES_ORDER".equals(receivable.subjectType())) return;
        boolean orderExists;
        try {
            if (salesOrders == null) throw new IllegalStateException("Sales Commitment query boundary is not configured");
            salesOrders.get(payment.tenantId(), payment.workspaceId(), receivable.subjectId());
            orderExists = true;
        } catch (CommercialBusinessException exception) {
            if (!"SALES_ORDER_NOT_FOUND".equals(exception.code())) throw exception;
            orderExists = false;
        }
        if (orderExists) return;
        UUID caseId = UUID.randomUUID();
        int inserted = jdbc.update("insert into payments.payment_reconciliation_case(id,tenant_id,workspace_id,payment_id,receivable_id,allocation_status,state,created_at,updated_at) values (?,?,?,?,?,'UNALLOCATED','RECONCILIATION_REQUIRED',current_timestamp,current_timestamp) on conflict (tenant_id,workspace_id,payment_id) do nothing",
                caseId, payment.tenantId(), payment.workspaceId(), payment.id(), receivable.id());
        if (inserted == 0) return;
        jdbc.update("update payments.payment_reconciliation_case set state='REFUND_PENDING',updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and state='RECONCILIATION_REQUIRED'",
                payment.tenantId(), payment.workspaceId(), caseId);
    }

    private PaymentModels.ReconciliationCaseView reconciliationCase(CurrentAccessContext context, UUID caseId) {
        return reconciliationCase(tenant(context), workspace(context), caseId);
    }

    private PaymentModels.ReconciliationCaseView reconciliationCase(UUID tenantId, UUID workspaceId, UUID caseId) {
        return jdbc.query(reconciliationCaseSql() + " where c.tenant_id=? and c.workspace_id=? and c.id=?",
                (rs, n) -> reconciliationCaseView(rs), tenantId, workspaceId, caseId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Reconciliation case not found"));
    }

    private static String reconciliationCaseSql() {
        return "select c.id,c.payment_id,c.receivable_id,c.sales_order_id,c.allocation_status,c.state,c.provider_refund_id,c.attempt_count,c.last_error,c.operator_note,c.created_at,c.updated_at,c.resolved_at,c.lease_until from payments.payment_reconciliation_case c";
    }

    private static PaymentModels.ReconciliationCaseView reconciliationCaseView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentModels.ReconciliationCaseView(rs.getObject("id", UUID.class).toString(), rs.getObject("payment_id", UUID.class).toString(),
                rs.getObject("receivable_id", UUID.class).toString(), rs.getObject("sales_order_id", UUID.class) == null ? null : rs.getObject("sales_order_id", UUID.class).toString(),
                rs.getString("allocation_status"), rs.getString("state"), rs.getString("provider_refund_id"), rs.getInt("attempt_count"),
                rs.getString("last_error"), rs.getString("operator_note"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());
    }

    private static ReconciliationCaseRow reconciliationCaseRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReconciliationCaseRow(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class), rs.getObject("receivable_id", UUID.class),
                rs.getString("state"), rs.getInt("attempt_count"), rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant());
    }

    private PaymentModels.ReconciliationCaseView decodeRetryResult(String raw) {
        try { return objectMapper.readValue(raw, PaymentModels.ReconciliationCaseView.class); }
        catch (Exception exception) { throw new IllegalStateException("Stored payment reconciliation result is invalid", exception); }
    }

    private void applySucceededPayment(CurrentAccessContext context, UUID paymentId, UUID receivableId, BigDecimal amount, String currency, String eventKey) {
        PaymentRow payment = new PaymentRow(paymentId, receivableId, "SUCCEEDED", amount, currency, null, null, Instant.now(), Instant.now(), null, tenant(context), workspace(context));
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update", (rs, n) -> receivableRow(rs), tenant(context), workspace(context), receivableId).stream().findFirst().orElseThrow();
        if (!receivable.currency().equalsIgnoreCase(currency)) throw new IllegalArgumentException("Payment currency does not match receivable");
        receivableApplications.apply(new ReceivableApplicationCommands.Request(tenant(context), workspace(context),
                context.membershipId().value(), receivable.id(), paymentId, amount, currency, eventKey, Instant.now()));
        enqueuePaymentReceipt(payment, receivable, eventKey);
        outbox(payment, "PAYMENT_SUCCEEDED", Map.of("paymentId", paymentId, "receivableId", receivableId, "amount", amount, "currency", currency));
    }

    private void enqueuePaymentReceipt(PaymentRow payment, ReceivableRow receivable, String eventKey) {
        UUID requester = jdbc.queryForObject("select created_by_membership_id from payments.payment where tenant_id=? and workspace_id=? and id=?", UUID.class, tenant(payment), workspace(payment), payment.id());
        if (documents == null) throw new IllegalStateException("Business Documents command boundary is not configured");
        documents.enqueuePaymentReceipt(tenant(payment), workspace(payment), payment.id(), receivable.clientAccountId(), requester, eventKey, Instant.now());
    }

    private void outbox(PaymentRow payment, String eventType, Map<String, Object> payload) { CanonicalOutbox.append(jdbc, eventType, "Payment", payment.id(), tenant(payment), workspace(payment), Instant.now(), "payment-" + payment.id(), null, "1.0", payload); }
    private String json(Object payload) { try { return objectMapper.writeValueAsString(payload); } catch (Exception exception) { throw new IllegalStateException("Payment JSON serialization failed", exception); } }

    private PaymentModels.ReceivableView receivableView(ReceivableRow row) {
        BigDecimal adjustedAmount = row.amount().add(row.adjustmentTotal());
        BigDecimal outstanding = adjustedAmount.subtract(row.amountPaid()).max(BigDecimal.ZERO);
        return new PaymentModels.ReceivableView(row.id().toString(), row.clientAccountId().toString(), row.subjectType(), row.subjectId().toString(), row.number(), row.currency(), row.amount(), row.amountPaid(), outstanding, row.status(), row.dueAt(), row.version());
    }

    private static BigDecimal payableAmount(ReceivableRow row) {
        return row.amount().add(row.adjustmentTotal()).subtract(row.amountPaid()).max(BigDecimal.ZERO);
    }

    private CardPaymentClaim prepareCardPaymentClaim(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        lockIdempotencyKey(context, idempotencyKey);
        ReceivableRow receivable = lockedReceivable(context, receivableId);
        ensureBuyerScope(context, receivable.clientAccountId());
        ExistingPayment existing = existingPayment(context, idempotencyKey);
        if (existing != null) {
            // A successful webhook may have closed the receivable before a client
            // retries the original request. Replay the durable payment claim
            // before evaluating whether the receivable is still payable.
            ensureIdempotentPayment(existing, receivable, PaymentMethod.CARD_STRIPE, existing.payment().amount());
            return new CardPaymentClaim(existing.payment(), receivable, minor(existing.payment().amount(), existing.payment().currency()),
                    providerIdempotencyKey(context, idempotencyKey), paymentMetadata(context, receivable), idempotencyKey);
        }
        BigDecimal amount = payableAmount(receivable);
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        ActiveCardPayment active = activeCardPayment(context, receivable.id());
        if (active != null) throw new PaymentOperationInProgressException();

        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, String> metadata = paymentMetadata(context, receivable);
        int inserted = jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,idempotency_key,metadata,created_at,updated_at) values (?,?,?,?,?,?, 'CARD_STRIPE','CREATED',?,?, 'STRIPE',?,?::jsonb,?,?) on conflict (tenant_id,workspace_id,created_by_membership_id,idempotency_key) do nothing",
                paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), amount, receivable.currency(), idempotencyKey, json(metadata), Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) {
            ExistingPayment concurrent = existingPayment(context, idempotencyKey);
            if (concurrent == null) throw new IllegalArgumentException("Payment idempotency claim was lost");
            ensureIdempotentPayment(concurrent, receivable, PaymentMethod.CARD_STRIPE, amount);
            return new CardPaymentClaim(concurrent.payment(), receivable, minor(amount, receivable.currency()),
                    providerIdempotencyKey(context, idempotencyKey), metadata, idempotencyKey);
        }
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,created_at) values (?,?,?,?,1,'CREATED',?)",
                UUID.randomUUID(), tenant(context), workspace(context), paymentId, Timestamp.from(now));
        PaymentRow payment = new PaymentRow(paymentId, receivable.id(), "CREATED", amount, receivable.currency(), null, null,
                now, null, receivable.clientAccountId(), tenant(context), workspace(context));
        return new CardPaymentClaim(payment, receivable, minor(amount, receivable.currency()),
                providerIdempotencyKey(context, idempotencyKey), metadata, idempotencyKey);
    }

    private PaymentRow persistProviderIntent(CurrentAccessContext context, CardPaymentClaim claim,
                                              StripePaymentProvider.PaymentIntent intent) {
        PaymentViewRow stored = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id,p.method,p.idempotency_key from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=? for update",
                (rs, n) -> new PaymentViewRow(paymentRow(rs), rs.getString("method")), tenant(context), workspace(context), claim.payment().id())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Payment idempotency claim is no longer available"));
        ensureIdempotentPayment(new ExistingPayment(stored.payment(), stored.method()), claim.receivable(), PaymentMethod.CARD_STRIPE, claim.payment().amount());
        if (stored.payment().providerId() != null && !stored.payment().providerId().equals(intent.providerId())) {
            throw new IllegalStateException("Payment provider idempotency claim returned a different PaymentIntent");
        }
        PaymentStatus status = initialPaymentStatus(intent.status());
        int updated = jdbc.update("update payments.payment set status=?,provider=?,provider_payment_intent_id=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and method='CARD_STRIPE' and idempotency_key=? and (provider_payment_intent_id is null or provider_payment_intent_id=?)",
                status.name(), "STRIPE", intent.providerId(), Timestamp.from(Instant.now()), tenant(context), workspace(context), claim.payment().id(), claim.idempotencyKey(), intent.providerId());
        if (updated != 1) throw new IllegalStateException("Payment provider result lost its durable claim");
        recordProviderAttempt(context, claim.payment().id(), status.name(), intent.providerId(), null, null);
        return new PaymentRow(claim.payment().id(), claim.receivable().id(), status.name(), claim.payment().amount(), claim.receivable().currency(),
                intent.clientSecret(), intent.providerId(), claim.payment().createdAt(), null, claim.receivable().clientAccountId(), tenant(context), workspace(context));
    }

    private void recordProviderFailure(CurrentAccessContext context, CardPaymentClaim claim, RuntimeException exception) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                PaymentViewRow stored = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id,p.method,p.idempotency_key from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=? for update",
                        (rs, n) -> new PaymentViewRow(paymentRow(rs), rs.getString("method")), tenant(context), workspace(context), claim.payment().id())
                        .stream().findFirst().orElse(null);
                if (stored == null || stored.payment().providerId() != null) return;
                String failureCode = exception instanceof TechnicalFailureException technical
                        ? technical.kind().name() : "PROVIDER_REQUEST_FAILED";
                jdbc.update("update payments.payment set status='FAILED',updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and provider_payment_intent_id is null",
                        Timestamp.from(Instant.now()), tenant(context), workspace(context), claim.payment().id());
                recordProviderAttempt(context, claim.payment().id(), "FAILED", null, failureCode, truncate(exception.getMessage()));
            });
        } catch (RuntimeException ignored) {
            // Preserve original provider failure; next idempotent request reconciles durable claim.
        }
    }

    private ConfirmationClaim prepareConfirmationClaim(CurrentAccessContext context, UUID receivableId) {
        ReceivableRow receivable = lockedReceivable(context, receivableId);
        ensureBuyerScope(context, receivable.clientAccountId());
        return new ConfirmationClaim(latestStripePayment(context, receivableId), receivable);
    }

    private void recordProviderAttempt(CurrentAccessContext context, UUID paymentId, String status,
                                       String providerReference, String failureCode, String failureDetail) {
        Boolean alreadyRecorded = jdbc.queryForObject("select exists(select 1 from payments.payment_attempt where tenant_id=? and workspace_id=? and payment_id=? and provider_reference is not distinct from ? and status=?)",
                Boolean.class, tenant(context), workspace(context), paymentId, providerReference, status);
        if (Boolean.TRUE.equals(alreadyRecorded)) return;
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,provider_reference,failure_code,failure_detail,created_at) select ?,tenant_id,workspace_id,id,coalesce((select max(a.attempt_number)+1 from payments.payment_attempt a where a.payment_id=p.id),1),?,?,?,?,current_timestamp from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=?",
                UUID.randomUUID(), status, providerReference, failureCode, failureDetail, tenant(context), workspace(context), paymentId);
    }

    private ActiveCardPayment activeCardPayment(CurrentAccessContext context, UUID receivableId) {
        return jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id,p.method,p.idempotency_key from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.receivable_id=? and p.method='CARD_STRIPE' and p.status in ('CREATED','REQUIRES_ACTION','PROCESSING') order by p.created_at asc limit 1",
                (rs, n) -> new ActiveCardPayment(paymentRow(rs), rs.getString("idempotency_key")), tenant(context), workspace(context), receivableId)
                .stream().findFirst().orElse(null);
    }

    private Map<String, String> paymentMetadata(CurrentAccessContext context, ReceivableRow receivable) {
        return Map.of("nexa_receivable_id", receivable.id().toString(), "nexa_tenant_id", tenant(context).toString(), "nexa_workspace_id", workspace(context).toString());
    }

    private String providerIdempotencyKey(CurrentAccessContext context, String idempotencyKey) {
        return "nexa-" + sha256("stripe-payment|" + tenant(context) + "|" + workspace(context) + "|" + context.membershipId().value() + "|" + idempotencyKey);
    }

    private PaymentModels.PaymentIntentView paymentIntentView(PaymentRow row, String clientSecret) {
        Payment aggregate = paymentAggregate(row);
        return new PaymentModels.PaymentIntentView(row.id().toString(), row.receivableId().toString(), aggregate.status().name(), aggregate.amount(), row.currency(), clientSecret, publishableKey, row.providerId(), row.createdAt());
    }
    private PaymentModels.PaymentView paymentView(PaymentRow row, String method) {
        Payment aggregate = paymentAggregate(row);
        return new PaymentModels.PaymentView(row.id().toString(), row.receivableId().toString(), method, aggregate.status().name(), aggregate.amount(), row.currency(), row.createdAt(), row.completedAt());
    }
    private Payment paymentAggregate(PaymentRow row) {
        return Payment.rehydrate(row.id().toString(), row.amount(), PaymentStatus.valueOf(row.status()));
    }
    private ExistingPayment existingPayment(CurrentAccessContext context, String idempotencyKey) {
        return jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id,p.method from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.created_by_membership_id=? and p.idempotency_key=?", (rs, n) -> new ExistingPayment(paymentRow(rs), rs.getString("method")), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey).stream().findFirst().orElse(null);
    }
    private void ensureBankTransferIdempotency(ExistingPayment existing, ReceivableRow receivable,
                                               String transferReference, UUID proofEvidenceId) {
        PaymentRow payment = existing.payment();
        if (!PaymentMethod.BANK_TRANSFER.name().equals(existing.method())
                || !receivable.id().equals(payment.receivableId())
                || !payment.currency().equalsIgnoreCase(receivable.currency())) {
            throw new PaymentIdempotencyPayloadConflictException();
        }
        BankTransferPayload stored = jdbc.query("select bank_transfer_reference,bank_transfer_proof_evidence_id "
                        + "from payments.payment where tenant_id=? and workspace_id=? and id=?",
                (rs, n) -> new BankTransferPayload(rs.getString("bank_transfer_reference"),
                        rs.getObject("bank_transfer_proof_evidence_id", UUID.class)),
                payment.tenantId(), payment.workspaceId(), payment.id()).stream().findFirst()
                .orElseThrow(() -> new PaymentIdempotencyPayloadConflictException());
        if (!Objects.equals(stored.reference(), transferReference.trim())
                || !Objects.equals(stored.proofEvidenceId(), proofEvidenceId)) {
            throw new PaymentIdempotencyPayloadConflictException();
        }
    }
    private void ensureIdempotentPayment(ExistingPayment existing, ReceivableRow receivable, PaymentMethod method, BigDecimal amount) {
        PaymentRow payment = existing.payment();
        if (!method.name().equals(existing.method()) || !receivable.id().equals(payment.receivableId()) || payment.amount().compareTo(amount) != 0 || !payment.currency().equalsIgnoreCase(receivable.currency())) {
            throw new IllegalArgumentException("Idempotency-Key was already used with a different payment request");
        }
    }
    private void ensureCanonicalReceivable(PaymentModels.ReceivableView existing, AuthoritativeSubject subject) {
        if (!subject.clientAccountId().toString().equals(existing.clientAccountId())
                || subject.amount().compareTo(existing.amount()) != 0
                || !subject.currency().equalsIgnoreCase(existing.currency())) {
            throw new IllegalArgumentException("Receivable does not match the canonical Sales Order");
        }
    }
    private void lockIdempotencyKey(CurrentAccessContext context, String idempotencyKey) {
        String lockKey = tenant(context) + ":" + workspace(context) + ":" + context.membershipId().value() + ":" + idempotencyKey;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, lockKey);
    }
    private <T> T executeIdempotentDatabaseTransaction(String operation, String idempotencyKey, Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_DATABASE_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                T result = transactionTemplate.execute(status -> work.get());
                countDatabaseTransaction(operation, attempt == 1 ? "success" : "retry_success");
                return result;
            } catch (RuntimeException exception) {
                last = exception;
                if (!retryableDatabaseFailure(exception) || attempt == MAX_DATABASE_TRANSACTION_ATTEMPTS) {
                    countDatabaseTransaction(operation, "failed");
                    throw exception;
                }
                countDatabaseTransaction(operation, "retry");
                LOGGER.warn("Retrying idempotent database transaction operation={} attempt={} idempotencyHash={}",
                        operation, attempt + 1, sha256(idempotencyKey));
            }
        }
        throw last == null ? new IllegalStateException("Database transaction retry failed") : last;
    }
    private void countDatabaseTransaction(String operation, String outcome) {
        if (metrics != null) metrics.count("payments", "idempotent_db_transaction_" + operation, outcome);
    }
    private static boolean retryableDatabaseFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && ("40001".equals(sqlException.getSQLState()) || "40P01".equals(sqlException.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
    private static String normalizeReviewAction(String action) {
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT", "RECONCILE").contains(normalized)) {
            throw new IllegalArgumentException("Bank transfer review action is invalid");
        }
        return normalized;
    }
    private static String normalizeReviewReason(String action, String reason) {
        if (reason == null || reason.isBlank()) {
            if ("REJECT".equals(action)) throw new IllegalArgumentException("Bank transfer rejection reason is required");
            return null;
        }
        String normalized = reason.trim();
        if (normalized.length() > 1000) throw new IllegalArgumentException("Bank transfer review reason is too long");
        return normalized;
    }
    private static void requireProviderIntent(StripePaymentProvider.PaymentIntent intent) {
        if (intent == null || intent.providerId() == null || intent.providerId().isBlank() || intent.status() == null || intent.status().isBlank()
                || intent.clientSecret() == null || intent.clientSecret().isBlank()) {
            throw new IllegalArgumentException("Payment provider returned an incomplete PaymentIntent");
        }
    }
    private static PaymentStatus initialPaymentStatus(String providerStatus) {
        PaymentStatus status = providerStatus(providerStatus);
        return status == PaymentStatus.SUCCEEDED ? PaymentStatus.PROCESSING : status;
    }
    private static UUID metadataUuid(StripePaymentProvider.StripeWebhookEvent event, String key) {
        String value = event.metadata().get(key);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Stripe webhook metadata " + key + " is invalid", exception);
        }
    }
    private static void requireWebhookIdentity(StripePaymentProvider.StripeWebhookEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 255
                || event.eventType() == null || event.eventType().isBlank() || event.eventType().length() > 160) {
            throw new IllegalArgumentException("Stripe webhook identity is invalid");
        }
        if (event.paymentIntentId() != null && event.paymentIntentId().length() > 255) {
            throw new IllegalArgumentException("Stripe PaymentIntent id is invalid");
        }
    }
    private String providerSecret(PaymentRow row) {
        if (row.providerId() == null) return null;
        return stripe.retrievePaymentIntent(row.providerId()).map(StripePaymentProvider.PaymentIntent::clientSecret).orElse(null);
    }
    private PaymentRow latestStripePayment(CurrentAccessContext context, UUID receivableId) {
        return jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.receivable_id=? and p.method='CARD_STRIPE' order by p.created_at desc limit 1", (rs, n) -> paymentRow(rs), tenant(context), workspace(context), receivableId).stream().findFirst().orElse(null);
    }
    private String testSucceededPayload(StripePaymentProvider.PaymentIntent intent, PaymentRow payment, ReceivableRow receivable, CurrentAccessContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nexa_receivable_id", receivable.id().toString());
        metadata.put("nexa_tenant_id", tenant(context).toString());
        metadata.put("nexa_workspace_id", workspace(context).toString());
        Map<String, Object> paymentIntent = new LinkedHashMap<>();
        paymentIntent.put("id", intent.providerId());
        paymentIntent.put("object", "payment_intent");
        paymentIntent.put("amount", minor(payment.amount(), payment.currency()));
        paymentIntent.put("amount_received", minor(payment.amount(), payment.currency()));
        paymentIntent.put("currency", payment.currency().toLowerCase(Locale.ROOT));
        paymentIntent.put("livemode", false);
        paymentIntent.put("metadata", metadata);
        paymentIntent.put("status", "succeeded");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", paymentIntent);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_nexa_local_" + UUID.randomUUID().toString().replace("-", ""));
        event.put("object", "event");
        event.put("api_version", "2024-06-20");
        event.put("created", Instant.now().getEpochSecond());
        event.put("data", data);
        event.put("livemode", false);
        event.put("pending_webhooks", 1);
        event.put("type", "payment_intent.succeeded");
        return json(event);
    }
    private String signWebhook(String payload) {
        if (webhookSecret.isBlank()) throw new IllegalStateException("Stripe webhook secret is not configured");
        long timestamp = Instant.now().getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String digest = java.util.HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + digest;
        } catch (Exception exception) {
            throw new IllegalStateException("Stripe webhook signature could not be generated", exception);
        }
    }
    private List<ReceivableRow> receivableQuery(CurrentAccessContext c, UUID id) { return jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=?", (rs, n) -> receivableRow(rs), tenant(c), workspace(c), id); }
    private List<ReceivableRow> receivableQuery(CurrentAccessContext c, UUID subjectId, String subjectType) { return jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and subject_id=? and subject_type=?", (rs, n) -> receivableRow(rs), tenant(c), workspace(c), subjectId, subjectType); }
    private ReceivableRow lockedReceivable(CurrentAccessContext c, UUID id) { return jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update", (rs, n) -> receivableRow(rs), tenant(c), workspace(c), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Receivable not found")); }
    private PaymentRow paymentRow(java.sql.ResultSet rs) throws java.sql.SQLException { return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("receivable_id", UUID.class), rs.getString("status"), rs.getBigDecimal("amount"), rs.getString("currency"), null, rs.getString("provider_payment_intent_id"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(), rs.getObject("client_account_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class)); }
    private ReceivableRow receivableRow(java.sql.ResultSet rs) throws java.sql.SQLException { return new ReceivableRow(rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("receivable_number"), rs.getString("currency"), rs.getBigDecimal("amount"), rs.getBigDecimal("amount_paid"), rs.getBigDecimal("adjustment_total"), rs.getString("status"), rs.getTimestamp("due_at").toInstant(), rs.getLong("version")); }
    private AuthoritativeSubject authoritativeSubject(CurrentAccessContext context, String subjectType, UUID subjectId) {
        if (!"SALES_ORDER".equals(subjectType)) throw new IllegalArgumentException("Receivable subject must be a Sales Order");
        if (salesOrders == null) throw new IllegalStateException("Sales Commitment query boundary is not configured");
        SalesOrderFulfillmentQuery.Snapshot order = salesOrders.getForUpdate(tenant(context), workspace(context), subjectId);
        AuthoritativeSubject value = new AuthoritativeSubject(order.clientAccountId(), order.total(), order.currency(), order.status(), order.paymentOption());
        if (!("CONFIRMED".equals(value.status()) || ("PENDING".equals(value.status()) && "PREPAID".equalsIgnoreCase(value.paymentOption())))
                || value.clientAccountId() == null || value.amount() == null || value.amount().signum() <= 0
                || value.currency() == null || value.currency().isBlank()) {
            throw new IllegalArgumentException("Sales Order is not a confirmed payable subject");
        }
        return new AuthoritativeSubject(value.clientAccountId(), value.amount(), value.currency().trim().toUpperCase(Locale.ROOT), value.status(), value.paymentOption());
    }
    private boolean authorizedClient(CurrentAccessContext c, UUID clientAccountId) {
        if (!c.hasRole(MembershipRole.BUYER)) return true;
        return customerAccounts != null && customerAccounts.findActiveBuyerDetails(tenant(c).toString(), workspace(c).toString(), c.membershipId().value().toString())
                .map(value -> clientAccountId != null && clientAccountId.toString().equals(value.id())).orElse(false);
    }
    private void ensureBuyerScope(CurrentAccessContext c, UUID clientAccountId) { if (!authorizedClient(c, clientAccountId)) throw new IllegalArgumentException("Receivable is outside buyer scope"); }
    private void validateProofEvidence(CurrentAccessContext context, ReceivableRow receivable, UUID evidenceId) {
        if (evidenceId == null) return;
        if (businessEvidence == null || !businessEvidence.isAvailableForSubject(tenant(context), workspace(context), evidenceId,
                receivable.clientAccountId(), "RECEIVABLE", receivable.id())) {
            throw new IllegalArgumentException("Bank transfer proof evidence is not available or is bound to another subject");
        }
    }

    private void validateStoredProofEvidence(CurrentAccessContext context, PaymentRow payment) {
        UUID evidenceId = jdbc.queryForObject("select bank_transfer_proof_evidence_id from payments.payment where tenant_id=? and workspace_id=? and id=?", UUID.class, tenant(context), workspace(context), payment.id());
        if (evidenceId == null) return;
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,coalesce(adjustment_total,0) adjustment_total,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=?", (rs, n) -> receivableRow(rs), tenant(context), workspace(context), payment.receivableId()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Receivable not found"));
        validateProofEvidence(context, receivable, evidenceId);
    }
    private static PaymentStatus providerStatus(String value) { if (value == null) return PaymentStatus.REQUIRES_ACTION; return switch (value.toLowerCase(Locale.ROOT)) { case "succeeded" -> PaymentStatus.SUCCEEDED; case "processing" -> PaymentStatus.PROCESSING; case "canceled", "cancelled" -> PaymentStatus.CANCELLED; case "requires_payment_method", "requires_action" -> PaymentStatus.REQUIRES_ACTION; default -> PaymentStatus.CREATED; }; }
    private static PaymentStatus statusFromEvent(String type, String providerStatus) { if ("payment_intent.succeeded".equals(type)) return PaymentStatus.SUCCEEDED; if ("payment_intent.payment_failed".equals(type)) return PaymentStatus.FAILED; if ("payment_intent.processing".equals(type)) return PaymentStatus.PROCESSING; if ("payment_intent.canceled".equals(type)) return PaymentStatus.CANCELLED; return null; }
    private static long minor(BigDecimal amount, String currency) { int scale = SetOfZeroDecimalCurrencies.contains(currency.toUpperCase(Locale.ROOT)) ? 0 : 2; BigDecimal minor = amount.setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale); try { return minor.longValueExact(); } catch (ArithmeticException exception) { throw new IllegalArgumentException("Payment amount precision is invalid", exception); } }
    private static void requireKey(String key) { if (key == null || key.isBlank() || key.length() > 160) throw new IllegalArgumentException("Idempotency-Key is required"); }
    private static String sha256(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(digest); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static String truncate(String value) { return value == null ? "unknown" : value.substring(0, Math.min(1000, value.length())); }
    private static UUID tenant(CurrentAccessContext c) { return c.tenantId().value(); } private static UUID workspace(CurrentAccessContext c) { return c.workspaceId().value(); }
    private static UUID tenant(PaymentRow row) { return row.tenantId(); } private static UUID workspace(PaymentRow row) { return row.workspaceId(); }
    private static String normalizePaymentMethod(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CARD_STRIPE", "BANK_TRANSFER", "CREDIT_LINE").contains(normalized)) throw new IllegalArgumentException("Payment method filter is invalid");
        return normalized;
    }
    private static String normalizePaymentStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CREATED", "REQUIRES_ACTION", "PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "REFUNDED", "PARTIALLY_REFUNDED").contains(normalized)) throw new IllegalArgumentException("Payment status filter is invalid");
        return normalized;
    }
    private static String normalizeReconciliationState(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("RECONCILIATION_REQUIRED", "REFUND_PENDING", "REFUND_PROCESSING", "REFUNDED", "REFUND_FAILED", "RESOLVED").contains(normalized)) {
            throw new IllegalArgumentException("Reconciliation state filter is invalid");
        }
        return normalized;
    }
    private static final java.util.Set<String> SetOfOpen = java.util.Set.of("OPEN", "PARTIALLY_PAID", "OVERDUE");
    private static final java.util.Set<String> SetOfZeroDecimalCurrencies = java.util.Set.of("JPY", "KRW", "CLP");
    private static String reconciliationRefundKey(UUID caseId) { return "nexa-reconciliation-refund-" + caseId; }
    private static String reconciliationRetryHash(CurrentAccessContext context, UUID caseId, String operatorNote) {
        return sha256("reconciliation-refund-v1|" + tenant(context) + "|" + workspace(context) + "|"
                + context.membershipId().value() + "|" + caseId + "|" + operatorNote);
    }

    private record ReceivableRow(UUID id, UUID clientAccountId, String subjectType, UUID subjectId, String number, String currency, BigDecimal amount, BigDecimal amountPaid, BigDecimal adjustmentTotal, String status, Instant dueAt, long version) { }
    private record StripeEventRow(String id, String eventType, String paymentIntentId, String paymentStatus, Long amountMinor, String currency, UUID tenantId, UUID workspaceId) { }
    private record ExistingPayment(PaymentRow payment, String method) { }
    private record BankTransferPayload(String reference, UUID proofEvidenceId) { }
    private record StoredBankReview(String idempotencyKey, String action, String reason) { }
    private record ActiveCardPayment(PaymentRow payment, String idempotencyKey) { }
    private record CardPaymentClaim(PaymentRow payment, ReceivableRow receivable, long amountMinor,
                                    String providerIdempotencyKey, Map<String, String> metadata, String idempotencyKey) { }
    private record ConfirmationClaim(PaymentRow payment, ReceivableRow receivable) { }
    private enum InboxOutcome { PROCESSED, IGNORED }
    private record WebhookWork(String eventId, UUID tenantId, UUID workspaceId) { }
    private record WorkspaceScope(UUID tenantId, UUID workspaceId) { }
    private record ReconciliationCandidate(UUID caseId, UUID paymentId, String providerId, BigDecimal amount, String currency, int attemptCount) { }
    private record ReconciliationRefundWork(UUID caseId, UUID tenantId, UUID workspaceId, UUID paymentId,
                                            String providerId, BigDecimal amount, String currency, UUID claimToken,
                                            UUID actorMembershipId, String idempotencyKey, String requestHash) { }
    private record ReconciliationRetryClaim(ReconciliationRefundWork work, PaymentModels.ReconciliationCaseView storedResult,
                                            String failureKind) { }
    private record RetryIdempotencyRow(String requestHash, String resultStatus, String resultJson, String failureKind) { }
    private record PaymentViewRow(PaymentRow payment, String method) { }
    private record ReconciliationCaseRow(UUID id, UUID paymentId, UUID receivableId, String state, int attemptCount, Instant leaseUntil) { }
    private record AuthoritativeSubject(UUID clientAccountId, BigDecimal amount, String currency, String status, String paymentOption) { }
    private record PaymentRow(UUID id, UUID receivableId, String status, BigDecimal amount, String currency, String clientSecret, String providerId, Instant createdAt, Instant completedAt, UUID clientAccountId, UUID tenantId, UUID workspaceId) {
        private PaymentRow(UUID id, UUID receivableId, String status, BigDecimal amount, String currency, String clientSecret, String providerId, Instant createdAt, Instant completedAt, UUID clientAccountId) { this(id, receivableId, status, amount, currency, clientSecret, providerId, createdAt, completedAt, clientAccountId, null, null); }
    }

    private static final class InboxClaimLostException extends RuntimeException {
        private InboxClaimLostException() { super("Stripe inbox claim is no longer owned by this worker"); }
    }

    private static final class ReconciliationClaimLostException extends RuntimeException {
        private ReconciliationClaimLostException() { super("Payment reconciliation claim is no longer owned by this worker"); }
    }
}
