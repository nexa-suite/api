package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.port.PaymentPort;
import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.domain.model.payment.PaymentStatus;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistence adapter. Amounts, scope and final status stay server/webhook authoritative. */
@Profile("!test")
@Repository
public class PaymentService implements PaymentPort {
    private final JdbcTemplate jdbc;
    private final StripePaymentProvider stripe;
    private final String publishableKey;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentService(JdbcTemplate jdbc, StripePaymentProvider stripe,
                          @Value("${nexa.payments.publishable-key:}") String publishableKey,
                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.stripe = stripe; this.publishableKey = publishableKey == null ? "" : publishableKey;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
        return jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.client_secret,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id,p.method from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=?", (rs, n) -> new PaymentViewRow(paymentRow(rs), rs.getString("method")), tenant(context), workspace(context), paymentId).stream()
                .filter(row -> authorizedClient(context, row.payment().clientAccountId())).map(row -> paymentView(row.payment(), row.method())).findFirst().orElseThrow(() -> new IllegalArgumentException("Payment not found"));
    }

    @Transactional
    public PaymentModels.ReceivableView createReceivable(CurrentAccessContext context, PaymentPort.ReceivableCommand request) {
        context.requirePermission(PermissionKey.PAYMENT_RECONCILE);
        if (request.amount() == null || request.amount().signum() <= 0 || request.currency() == null || !request.currency().matches("[A-Z]{3}")) throw new IllegalArgumentException("Receivable amount or currency is invalid");
        if (request.clientAccountId() == null || request.subjectId() == null || request.subjectType() == null || request.subjectType().isBlank()) throw new IllegalArgumentException("Receivable subject is required");
        UUID id = UUID.randomUUID(); Instant now = Instant.now(); String number = "AR-" + now.toEpochMilli() + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("insert into payments.receivable (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,due_at,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,'OPEN',?,?)", id, tenant(context), workspace(context), request.clientAccountId(), request.subjectType().trim().toUpperCase(Locale.ROOT), request.subjectId(), number, request.currency(), request.amount(), Timestamp.from(request.dueAt() == null ? now.plusSeconds(30L * 24 * 60 * 60) : request.dueAt()), Timestamp.from(now), Timestamp.from(now));
        ensureCreditAccount(context, request.clientAccountId(), request.currency(), now);
        CanonicalOutbox.append(jdbc, "INVOICE_ISSUED", "Receivable", id, tenant(context), workspace(context), now,
                "receivable-" + id, null, "1.0", Map.of("receivableId", id, "subjectType", request.subjectType(),
                        "subjectId", request.subjectId(), "amount", request.amount(), "currency", request.currency()));
        return getReceivable(context, id);
    }

    @Transactional
    public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE); requireKey(idempotencyKey);
        PaymentRow existing = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.client_secret,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.created_by_membership_id=? and p.idempotency_key=?", (rs, n) -> paymentRow(rs), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey).stream().findFirst().orElse(null);
        if (existing != null) return paymentIntentView(existing);
        ReceivableRow receivable = lockedReceivable(context, receivableId);
        ensureBuyerScope(context, receivable.clientAccountId());
        BigDecimal amount = receivable.amount().subtract(receivable.amountPaid());
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        long amountMinor = minor(amount, receivable.currency());
        Map<String, String> metadata = Map.of("nexa_receivable_id", receivable.id().toString(), "nexa_tenant_id", tenant(context).toString(), "nexa_workspace_id", workspace(context).toString());
        StripePaymentProvider.PaymentIntent intent = stripe.createPaymentIntent(new StripePaymentProvider.PaymentIntentRequest(amountMinor, receivable.currency(), idempotencyKey, metadata));
        UUID paymentId = UUID.randomUUID(); Instant now = Instant.now(); PaymentStatus status = providerStatus(intent.status());
        jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,provider_payment_intent_id,client_secret,idempotency_key,metadata,created_at,updated_at) values (?,?,?,?,?,?, 'CARD_STRIPE',?,?,?,?,?,?,?,?::jsonb,?,?)", paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), status.name(), amount, receivable.currency(), "STRIPE", intent.providerId(), intent.clientSecret(), idempotencyKey, json(metadata), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,provider_reference,created_at) values (?,?,?,?,1,?,?,?)", UUID.randomUUID(), tenant(context), workspace(context), paymentId, status.name(), intent.providerId(), Timestamp.from(now));
        PaymentRow payment = new PaymentRow(paymentId, receivable.id(), status.name(), amount, receivable.currency(), intent.clientSecret(), intent.providerId(), now, null, receivable.clientAccountId(), tenant(context), workspace(context));
        return paymentIntentView(payment);
    }

    @Transactional
    public PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE); requireKey(idempotencyKey);
        PaymentRow existing = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.client_secret,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.created_by_membership_id=? and p.idempotency_key=?", (rs, n) -> paymentRow(rs), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey).stream().findFirst().orElse(null);
        if (existing != null) return paymentView(existing, "CREDIT_LINE");
        ReceivableRow receivable = lockedReceivable(context, receivableId); ensureBuyerScope(context, receivable.clientAccountId());
        BigDecimal amount = receivable.amount().subtract(receivable.amountPaid());
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        CreditRow credit = jdbc.query("select id,credit_limit,credit_exposure,reserved_exposure from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=? and status='ACTIVE' for update", (rs, n) -> new CreditRow(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)), tenant(context), workspace(context), receivable.clientAccountId(), receivable.currency()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Client credit account is not configured"));
        if (credit.limit().subtract(credit.exposure()).subtract(credit.reserved()).compareTo(amount) < 0) throw new IllegalArgumentException("Credit limit exceeded");
        UUID paymentId = UUID.randomUUID(); Instant now = Instant.now();
        jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,idempotency_key,created_at,updated_at,completed_at) values (?,?,?,?,?,?, 'CREDIT_LINE','SUCCEEDED',?,?, 'NEXA_CREDIT',?,?,?,?)", paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), amount, receivable.currency(), idempotencyKey, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into payments.credit_reservation (id,tenant_id,workspace_id,credit_account_id,receivable_id,payment_id,amount,status,idempotency_key,created_at) values (?,?,?,?,?,?,?,'CONSUMED',?,?)", UUID.randomUUID(), tenant(context), workspace(context), credit.id(), receivable.id(), paymentId, amount, idempotencyKey, Timestamp.from(now));
        if (jdbc.update("update payments.credit_account set credit_exposure=credit_exposure+?,version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and status='ACTIVE' and credit_exposure+reserved_exposure+?<=credit_limit", amount, Timestamp.from(now), tenant(context), workspace(context), credit.id(), amount) != 1) {
            throw new IllegalArgumentException("Credit limit exceeded");
        }
        applySucceededPayment(context, paymentId, receivable.id(), amount, receivable.currency(), "credit-line-" + paymentId);
        PaymentRow payment = new PaymentRow(paymentId, receivable.id(), PaymentStatus.SUCCEEDED.name(), amount, receivable.currency(), null, null, now, now, receivable.clientAccountId(), tenant(context), workspace(context));
        return paymentView(payment, "CREDIT_LINE");
    }

    @Transactional
    public PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE); requireKey(idempotencyKey);
        PaymentRow existing = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.client_secret,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.created_by_membership_id=? and p.idempotency_key=?", (rs, n) -> paymentRow(rs), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey).stream().findFirst().orElse(null);
        if (existing != null) return paymentView(existing, "BANK_TRANSFER");
        ReceivableRow receivable = lockedReceivable(context, receivableId); ensureBuyerScope(context, receivable.clientAccountId()); BigDecimal amount = receivable.amount().subtract(receivable.amountPaid());
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        UUID paymentId = UUID.randomUUID(); Instant now = Instant.now();
        jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,idempotency_key,created_at,updated_at) values (?,?,?,?,?,?, 'BANK_TRANSFER','PROCESSING',?,?, 'BANK_TRANSFER',?,?,?)", paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), amount, receivable.currency(), idempotencyKey, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,created_at) values (?,?,?,?,1,'PROCESSING',?)", UUID.randomUUID(), tenant(context), workspace(context), paymentId, Timestamp.from(now));
        return paymentView(new PaymentRow(paymentId, receivable.id(), "PROCESSING", amount, receivable.currency(), null, null, now, null, receivable.clientAccountId(), tenant(context), workspace(context)), "BANK_TRANSFER");
    }

    @Transactional
    public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) {
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("Stripe webhook payload is required");
        StripePaymentProvider.StripeWebhookEvent event = stripe.verifyWebhook(payload, signature);
        String signatureHash = sha256(signature == null ? "" : signature);
        int inserted = jdbc.update("insert into payments.stripe_event_inbox (event_id,event_type,payment_intent_id,payment_status,amount_minor,currency,signature_sha256,received_at) values (?,?,?,?,?,?,?,?) on conflict (event_id) do nothing", event.eventId(), event.eventType(), event.paymentIntentId(), event.paymentStatus(), event.amountMinor(), event.currency() == null ? null : event.currency().toUpperCase(Locale.ROOT), signatureHash, Timestamp.from(Instant.now()));
        return new PaymentModels.WebhookReceipt(event.eventId(), inserted == 0 ? "DUPLICATE" : "ACCEPTED");
    }

    @Scheduled(fixedDelayString = "${nexa.payments.webhook-worker-delay-ms:1000}")
    public void processStripeWebhookInbox() {
        jdbc.update("update payments.stripe_event_inbox set status='FAILED',failure_detail='Stale processing attempt',next_attempt_at=current_timestamp where status='PROCESSING' and received_at < current_timestamp - interval '10 minutes'");
        List<String> ids = jdbc.query("select event_id from payments.stripe_event_inbox where status in ('RECEIVED','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp order by received_at,event_id limit 20", (rs, n) -> rs.getString(1));
        for (String id : ids) {
            if (jdbc.update("update payments.stripe_event_inbox set status='PROCESSING',attempt_count=attempt_count+1,failure_detail=null where event_id=? and status in ('RECEIVED','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp", id) == 0) continue;
            try {
                transactionTemplate.executeWithoutResult(transaction -> {
                    processWebhook(id);
                    jdbc.update("update payments.stripe_event_inbox set status='PROCESSED',processed_at=current_timestamp,next_attempt_at=current_timestamp where event_id=?", id);
                });
            } catch (RuntimeException exception) {
                jdbc.update("update payments.stripe_event_inbox set status='FAILED',failure_detail=?,next_attempt_at=current_timestamp + (least(power(2,attempt_count),300) * interval '1 second') where event_id=?", truncate(exception.getMessage()), id);
            }
        }
    }

    @Transactional
    void processWebhook(String eventId) {
        StripeEventRow event = jdbc.query("select event_id,event_type,payment_intent_id,payment_status,amount_minor,currency from payments.stripe_event_inbox where event_id=?", (rs, n) -> new StripeEventRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, Long.class), rs.getString(6)), eventId).stream().findFirst().orElseThrow();
        if (event.paymentIntentId() == null || !event.eventType().startsWith("payment_intent.")) { jdbc.update("update payments.stripe_event_inbox set status='IGNORED',processed_at=current_timestamp where event_id=?", eventId); return; }
        PaymentRow payment = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.client_secret,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.provider_payment_intent_id=? for update", (rs, n) -> paymentRow(rs), event.paymentIntentId()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Stripe payment intent is not known"));
        if (event.amountMinor() != null && event.amountMinor() != minor(payment.amount(), payment.currency())) throw new IllegalArgumentException("Stripe amount does not match receivable payment");
        if (event.currency() != null && !event.currency().equalsIgnoreCase(payment.currency())) throw new IllegalArgumentException("Stripe currency does not match receivable payment");
        PaymentStatus next = statusFromEvent(event.eventType(), event.paymentStatus());
        if (next == null) { jdbc.update("update payments.stripe_event_inbox set status='IGNORED',processed_at=current_timestamp where event_id=?", eventId); return; }
        if (next == PaymentStatus.SUCCEEDED && !PaymentStatus.SUCCEEDED.name().equals(payment.status())) applySucceededPaymentForStoredContext(payment, eventId);
        jdbc.update("update payments.payment set status=?,updated_at=current_timestamp,completed_at=case when ?='SUCCEEDED' then current_timestamp else completed_at end,version=version+1 where id=?", next.name(), next.name(), payment.id());
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,provider_reference,failure_code,created_at) select ?,p.tenant_id,p.workspace_id,p.id,coalesce((select max(a.attempt_number)+1 from payments.payment_attempt a where a.payment_id=p.id),1),?,?,case when ?='FAILED' then 'PROVIDER_DECLINED' else null end,current_timestamp from payments.payment p where p.id=?", UUID.randomUUID(), next.name(), event.paymentIntentId(), next.name(), payment.id());
    }

    private void applySucceededPaymentForStoredContext(PaymentRow payment, String eventKey) {
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where id=? for update", (rs, n) -> receivableRow(rs), payment.receivableId()).stream().findFirst().orElseThrow();
        if (receivable.amount().subtract(receivable.amountPaid()).compareTo(payment.amount()) < 0) throw new IllegalArgumentException("Payment exceeds receivable balance");
        int inserted = jdbc.update("insert into payments.receivable_allocation (id,tenant_id,workspace_id,receivable_id,payment_id,amount,allocated_at) values (?,?,?,?,?,?,current_timestamp) on conflict (payment_id) do nothing", UUID.randomUUID(), tenant(payment), workspace(payment), receivable.id(), payment.id(), payment.amount());
        if (inserted == 0) return;
        jdbc.update("update payments.receivable set amount_paid=amount_paid+?,status=case when amount_paid+?=amount then 'PAID' else 'PARTIALLY_PAID' end,updated_at=current_timestamp,version=version+1 where id=?", payment.amount(), payment.amount(), receivable.id());
        jdbc.update("insert into payments.payment_event (id,tenant_id,workspace_id,payment_id,event_type,event_key,occurred_at) values (?,?,?,?,?,?,current_timestamp) on conflict (payment_id,event_key) do nothing", UUID.randomUUID(), tenant(payment), workspace(payment), payment.id(), "PAYMENT_SUCCEEDED", eventKey);
        enqueuePaymentReceipt(payment, receivable, eventKey);
        outbox(payment, "PAYMENT_SUCCEEDED", Map.of("paymentId", payment.id(), "receivableId", receivable.id(), "amount", payment.amount(), "currency", payment.currency()));
    }

    private void applySucceededPayment(CurrentAccessContext context, UUID paymentId, UUID receivableId, BigDecimal amount, String currency, String eventKey) {
        PaymentRow payment = new PaymentRow(paymentId, receivableId, "SUCCEEDED", amount, currency, null, null, Instant.now(), Instant.now(), null, tenant(context), workspace(context));
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where id=? for update", (rs, n) -> receivableRow(rs), receivableId).stream().findFirst().orElseThrow();
        jdbc.update("insert into payments.receivable_allocation (id,tenant_id,workspace_id,receivable_id,payment_id,amount,allocated_at) values (?,?,?,?,?,?,current_timestamp) on conflict (payment_id) do nothing", UUID.randomUUID(), tenant(context), workspace(context), receivableId, paymentId, amount);
        jdbc.update("update payments.receivable set amount_paid=amount_paid+?,status=case when amount_paid+?=amount then 'PAID' else 'PARTIALLY_PAID' end,updated_at=current_timestamp,version=version+1 where id=?", amount, amount, receivableId);
        enqueuePaymentReceipt(payment, receivable, eventKey);
        outbox(payment, "PAYMENT_SUCCEEDED", Map.of("paymentId", paymentId, "receivableId", receivableId, "amount", amount, "currency", currency));
    }

    private void enqueuePaymentReceipt(PaymentRow payment, ReceivableRow receivable, String eventKey) {
        UUID documentId = UUID.randomUUID(); UUID requestId = UUID.randomUUID(); Instant now = Instant.now();
        Integer version = jdbc.queryForObject("select coalesce(max(version),0)+1 from business_documents.business_document where tenant_id=? and workspace_id=? and subject_type='PAYMENT' and subject_id=? and document_type='PAYMENT_RECEIPT' and format='PDF'", Integer.class, tenant(payment), workspace(payment), payment.id());
        int inserted = jdbc.update("insert into business_documents.business_document (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,document_type,version,status,format,created_at,updated_at) values (?,?,?,?, 'PAYMENT',?,'PAYMENT_RECEIPT',?,'REQUESTED','PDF',?,?) on conflict do nothing", documentId, tenant(payment), workspace(payment), receivable.clientAccountId(), payment.id(), version, Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) return;
        UUID requester = jdbc.queryForObject("select created_by_membership_id from payments.payment where id=?", UUID.class, payment.id());
        jdbc.update("insert into business_documents.document_generation_request (id,tenant_id,workspace_id,requested_by_membership_id,document_id,subject_type,subject_id,document_type,format,status,idempotency_key,request_hash,requested_at) values (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?) on conflict do nothing", requestId, tenant(payment), workspace(payment), requester, documentId, "PAYMENT", payment.id(), "PAYMENT_RECEIPT", "PDF", "payment-receipt-" + payment.id(), sha256(eventKey), Timestamp.from(now));
    }

    private void outbox(PaymentRow payment, String eventType, Map<String, Object> payload) { CanonicalOutbox.append(jdbc, eventType, "Payment", payment.id(), tenant(payment), workspace(payment), Instant.now(), "payment-" + payment.id(), null, "1.0", payload); }
    private String json(Map<?, ?> payload) { try { return objectMapper.writeValueAsString(payload); } catch (Exception exception) { throw new IllegalStateException("Payment JSON serialization failed", exception); } }

    private PaymentModels.ReceivableView receivableView(ReceivableRow row) { return new PaymentModels.ReceivableView(row.id().toString(), row.clientAccountId().toString(), row.subjectType(), row.subjectId().toString(), row.number(), row.currency(), row.amount(), row.amountPaid(), row.amount().subtract(row.amountPaid()), row.status(), row.dueAt(), row.version()); }
    private PaymentModels.PaymentIntentView paymentIntentView(PaymentRow row) { return new PaymentModels.PaymentIntentView(row.id().toString(), row.receivableId().toString(), row.status(), row.amount(), row.currency(), row.clientSecret(), publishableKey, row.providerId(), row.createdAt()); }
    private PaymentModels.PaymentView paymentView(PaymentRow row, String method) { return new PaymentModels.PaymentView(row.id().toString(), row.receivableId().toString(), method, row.status(), row.amount(), row.currency(), row.providerId(), row.createdAt(), row.completedAt()); }
    private List<ReceivableRow> receivableQuery(CurrentAccessContext c, UUID id) { return jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=?", (rs, n) -> receivableRow(rs), tenant(c), workspace(c), id); }
    private ReceivableRow lockedReceivable(CurrentAccessContext c, UUID id) { return jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update", (rs, n) -> receivableRow(rs), tenant(c), workspace(c), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Receivable not found")); }
    private PaymentRow paymentRow(java.sql.ResultSet rs) throws java.sql.SQLException { return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("receivable_id", UUID.class), rs.getString("status"), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("client_secret"), rs.getString("provider_payment_intent_id"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(), rs.getObject("client_account_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class)); }
    private ReceivableRow receivableRow(java.sql.ResultSet rs) throws java.sql.SQLException { return new ReceivableRow(rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("receivable_number"), rs.getString("currency"), rs.getBigDecimal("amount"), rs.getBigDecimal("amount_paid"), rs.getString("status"), rs.getTimestamp("due_at").toInstant(), rs.getLong("version")); }
    private boolean authorizedClient(CurrentAccessContext c, UUID clientAccountId) { return !c.hasRole(MembershipRole.BUYER) || Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.client_account_membership where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=?)", Boolean.class, tenant(c), workspace(c), clientAccountId, c.membershipId().value())); }
    private void ensureBuyerScope(CurrentAccessContext c, UUID clientAccountId) { if (!authorizedClient(c, clientAccountId)) throw new IllegalArgumentException("Receivable is outside buyer scope"); }
    private void ensureCreditAccount(CurrentAccessContext c, UUID clientAccountId, String currency, Instant now) { jdbc.update("insert into payments.credit_account (id,tenant_id,workspace_id,client_account_id,currency,credit_limit,created_at,updated_at) values (?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,client_account_id,currency) do nothing", UUID.randomUUID(), tenant(c), workspace(c), clientAccountId, currency, BigDecimal.ZERO, Timestamp.from(now), Timestamp.from(now)); }
    private static PaymentStatus providerStatus(String value) { if (value == null) return PaymentStatus.REQUIRES_ACTION; return switch (value.toLowerCase(Locale.ROOT)) { case "succeeded" -> PaymentStatus.SUCCEEDED; case "processing" -> PaymentStatus.PROCESSING; case "canceled", "cancelled" -> PaymentStatus.CANCELLED; case "requires_payment_method", "requires_action" -> PaymentStatus.REQUIRES_ACTION; default -> PaymentStatus.CREATED; }; }
    private static PaymentStatus statusFromEvent(String type, String providerStatus) { if ("payment_intent.succeeded".equals(type)) return PaymentStatus.SUCCEEDED; if ("payment_intent.payment_failed".equals(type)) return PaymentStatus.FAILED; if ("payment_intent.processing".equals(type)) return PaymentStatus.PROCESSING; if ("payment_intent.canceled".equals(type)) return PaymentStatus.CANCELLED; return providerStatus == null ? null : providerStatus(providerStatus); }
    private static long minor(BigDecimal amount, String currency) { int scale = SetOfZeroDecimalCurrencies.contains(currency.toUpperCase(Locale.ROOT)) ? 0 : 2; BigDecimal minor = amount.setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale); try { return minor.longValueExact(); } catch (ArithmeticException exception) { throw new IllegalArgumentException("Payment amount precision is invalid", exception); } }
    private static void requireKey(String key) { if (key == null || key.isBlank() || key.length() > 160) throw new IllegalArgumentException("Idempotency-Key is required"); }
    private static String sha256(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(digest); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static String truncate(String value) { return value == null ? "unknown" : value.substring(0, Math.min(1000, value.length())); }
    private static UUID tenant(CurrentAccessContext c) { return c.tenantId().value(); } private static UUID workspace(CurrentAccessContext c) { return c.workspaceId().value(); }
    private static UUID tenant(PaymentRow row) { return row.tenantId(); } private static UUID workspace(PaymentRow row) { return row.workspaceId(); }
    private static final java.util.Set<String> SetOfOpen = java.util.Set.of("OPEN", "PARTIALLY_PAID", "OVERDUE");
    private static final java.util.Set<String> SetOfZeroDecimalCurrencies = java.util.Set.of("JPY", "KRW", "CLP");

    private record ReceivableRow(UUID id, UUID clientAccountId, String subjectType, UUID subjectId, String number, String currency, BigDecimal amount, BigDecimal amountPaid, String status, Instant dueAt, long version) { }
    private record CreditRow(UUID id, BigDecimal limit, BigDecimal exposure, BigDecimal reserved) { }
    private record StripeEventRow(String id, String eventType, String paymentIntentId, String paymentStatus, Long amountMinor, String currency) { }
    private record PaymentViewRow(PaymentRow payment, String method) { }
    private record PaymentRow(UUID id, UUID receivableId, String status, BigDecimal amount, String currency, String clientSecret, String providerId, Instant createdAt, Instant completedAt, UUID clientAccountId, UUID tenantId, UUID workspaceId) {
        private PaymentRow(UUID id, UUID receivableId, String status, BigDecimal amount, String currency, String clientSecret, String providerId, Instant createdAt, Instant completedAt, UUID clientAccountId) { this(id, receivableId, status, amount, currency, clientSecret, providerId, createdAt, completedAt, clientAccountId, null, null); }
    }
}
