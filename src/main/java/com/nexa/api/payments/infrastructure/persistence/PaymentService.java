package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.port.PaymentPersistencePort;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.domain.model.credit.CreditAccount;
import com.nexa.api.payments.domain.model.credit.CreditReservation;
import com.nexa.api.payments.domain.model.payment.Payment;
import com.nexa.api.payments.domain.model.payment.PaymentMethod;
import com.nexa.api.payments.domain.model.payment.PaymentStatus;
import com.nexa.api.payments.domain.model.receivable.Receivable;
import com.nexa.api.payments.domain.model.receivable.ReceivableAllocation;
import com.nexa.api.payments.domain.model.receivable.ReceivableStatus;
import com.nexa.api.shared.infrastructure.security.RlsRequestScope;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Persistence adapter for payment use cases. Amounts, scope and final status stay server/webhook authoritative. */
@Profile("!test")
@Component
public class PaymentService implements PaymentPersistencePort {
    private final JdbcTemplate jdbc;
    private final StripePaymentProvider stripe;
    private final String publishableKey;
    private final String webhookSecret;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentService(JdbcTemplate jdbc, StripePaymentProvider stripe,
                          @Value("${nexa.payments.publishable-key:}") String publishableKey,
                          @Value("${nexa.payments.webhook-secret:}") String webhookSecret,
                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.stripe = stripe; this.publishableKey = publishableKey == null ? "" : publishableKey;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
            String scope = " and exists (select 1 from sales.client_account_membership cam where cam.tenant_id=r.tenant_id and cam.workspace_id=r.workspace_id and cam.client_account_id=r.client_account_id and cam.workspace_membership_id=?)";
            rows = jdbc.query("select r.id,r.client_account_id,r.subject_type,r.subject_id,r.receivable_number,r.currency,r.amount,r.amount_paid,r.status,r.due_at,r.version from payments.receivable r where r.tenant_id=? and r.workspace_id=?" + scope + " order by r.due_at nulls last,r.created_at desc limit ? offset ?", (rs, n) -> receivableRow(rs), tenant(context), workspace(context), context.membershipId().value(), safeSize, offset);
            total = jdbc.queryForObject("select count(*) from payments.receivable r where r.tenant_id=? and r.workspace_id=?" + scope, Long.class, tenant(context), workspace(context), context.membershipId().value());
        } else {
            rows = jdbc.query("select r.id,r.client_account_id,r.subject_type,r.subject_id,r.receivable_number,r.currency,r.amount,r.amount_paid,r.status,r.due_at,r.version from payments.receivable r where r.tenant_id=? and r.workspace_id=? order by r.due_at nulls last,r.created_at desc limit ? offset ?", (rs, n) -> receivableRow(rs), tenant(context), workspace(context), safeSize, offset);
            total = jdbc.queryForObject("select count(*) from payments.receivable r where r.tenant_id=? and r.workspace_id=?", Long.class, tenant(context), workspace(context));
        }
        List<PaymentModels.ReceivableView> visible = rows.stream().filter(row -> authorizedClient(context, row.clientAccountId())).map(this::receivableView).toList();
        return new PaymentModels.Page<>(visible, safePage, safeSize, total == null ? 0 : total);
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

    @Transactional
    public PaymentModels.ReceivableView createReceivable(CurrentAccessContext context, PaymentPersistencePort.ReceivableCommand request) {
        context.requirePermission(PermissionKey.PAYMENT_RECONCILE);
        requireKey(request.idempotencyKey());
        lockIdempotencyKey(context, request.idempotencyKey());
        if (request.subjectId() == null || request.subjectType() == null || request.subjectType().isBlank()) throw new IllegalArgumentException("Receivable subject is required");
        String subjectType = request.subjectType().trim().toUpperCase(Locale.ROOT);
        AuthoritativeSubject subject = authoritativeSubject(context, subjectType, request.subjectId());
        PaymentModels.ReceivableView existing = receivableQuery(context, request.subjectId(), subjectType).stream()
                .filter(row -> authorizedClient(context, row.clientAccountId())).findFirst().map(this::receivableView).orElse(null);
        if (existing != null) {
            ensureCanonicalReceivable(existing, subject);
            return existing;
        }
        UUID id = UUID.randomUUID(); Instant now = Instant.now(); String number = "AR-" + now.toEpochMilli() + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Receivable aggregate = Receivable.rehydrate(id.toString(), subject.amount(), BigDecimal.ZERO, ReceivableStatus.OPEN);
        int inserted = jdbc.update("insert into payments.receivable (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,due_at,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,'OPEN',?,?) on conflict (tenant_id,workspace_id,subject_type,subject_id) do nothing", id, tenant(context), workspace(context), subject.clientAccountId(), subjectType, request.subjectId(), number, subject.currency(), aggregate.amount(), Timestamp.from(request.dueAt() == null ? now.plusSeconds(30L * 24 * 60 * 60) : request.dueAt()), Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) {
            return receivableQuery(context, request.subjectId(), subjectType).stream().filter(row -> authorizedClient(context, row.clientAccountId()))
                    .findFirst().map(row -> { PaymentModels.ReceivableView value = receivableView(row); ensureCanonicalReceivable(value, subject); return value; })
                    .orElseThrow(() -> new IllegalArgumentException("Receivable could not be created"));
        }
        ensureCreditAccount(context, subject.clientAccountId(), subject.currency(), now);
        CanonicalOutbox.append(jdbc, "RECEIVABLE_CREATED", "Receivable", id, tenant(context), workspace(context), now,
                "receivable-" + id, null, "1.0", Map.of("receivableId", id, "subjectType", subjectType,
                        "subjectId", request.subjectId(), "amount", subject.amount(), "currency", subject.currency()));
        return getReceivable(context, id);
    }

    @Transactional
    public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE); requireKey(idempotencyKey); lockIdempotencyKey(context, idempotencyKey);
        ReceivableRow receivable = lockedReceivable(context, receivableId);
        ensureBuyerScope(context, receivable.clientAccountId());
        BigDecimal amount = receivable.amount().subtract(receivable.amountPaid());
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        ExistingPayment existing = existingPayment(context, idempotencyKey);
        if (existing != null) {
            ensureIdempotentPayment(existing, receivable, PaymentMethod.CARD_STRIPE, amount);
            return paymentIntentView(existing.payment(), providerSecret(existing.payment()));
        }
        long amountMinor = minor(amount, receivable.currency());
        Map<String, String> metadata = Map.of("nexa_receivable_id", receivable.id().toString(), "nexa_tenant_id", tenant(context).toString(), "nexa_workspace_id", workspace(context).toString());
        StripePaymentProvider.PaymentIntent intent = stripe.createPaymentIntent(new StripePaymentProvider.PaymentIntentRequest(amountMinor, receivable.currency(), idempotencyKey, metadata));
        requireProviderIntent(intent);
        UUID paymentId = UUID.randomUUID(); Instant now = Instant.now(); PaymentStatus status = initialPaymentStatus(intent.status());
        Payment aggregate = Payment.rehydrate(paymentId.toString(), amount, status);
        int inserted = jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,provider_payment_intent_id,idempotency_key,metadata,created_at,updated_at) values (?,?,?,?,?,?, 'CARD_STRIPE',?,?,?,?,?,?,?::jsonb,?,?) on conflict (tenant_id,workspace_id,created_by_membership_id,idempotency_key) do nothing", paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), aggregate.status().name(), aggregate.amount(), receivable.currency(), "STRIPE", intent.providerId(), idempotencyKey, json(metadata), Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) {
            ExistingPayment concurrent = existingPayment(context, idempotencyKey);
            if (concurrent == null) throw new IllegalArgumentException("Payment idempotency claim was lost");
            ensureIdempotentPayment(concurrent, receivable, PaymentMethod.CARD_STRIPE, amount);
            return paymentIntentView(concurrent.payment(), providerSecret(concurrent.payment()));
        }
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,provider_reference,created_at) values (?,?,?,?,1,?,?,?)", UUID.randomUUID(), tenant(context), workspace(context), paymentId, status.name(), intent.providerId(), Timestamp.from(now));
        PaymentRow payment = new PaymentRow(paymentId, receivable.id(), aggregate.status().name(), aggregate.amount(), receivable.currency(), intent.clientSecret(), intent.providerId(), now, null, receivable.clientAccountId(), tenant(context), workspace(context));
        return paymentIntentView(payment, intent.clientSecret());
    }

    /**
     * Local-only browser acceptance seam. It exercises the official Stripe
     * adapter against the configured Stripe-compatible provider, then sends a
     * signed event through the same webhook inbox/worker used in production.
     * There is no route for this outside the local profile.
     */
    @Transactional
    public PaymentModels.PaymentView confirmTestCardPayment(CurrentAccessContext context, UUID receivableId, String clientSecret) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE);
        if (clientSecret == null || clientSecret.isBlank() || clientSecret.length() > 512) throw new IllegalArgumentException("Stripe client secret is required");
        ReceivableRow receivable = lockedReceivable(context, receivableId);
        ensureBuyerScope(context, receivable.clientAccountId());
        PaymentRow payment = latestStripePayment(context, receivableId);
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

    @Transactional
    public PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE); requireKey(idempotencyKey); lockIdempotencyKey(context, idempotencyKey);
        ReceivableRow receivable = lockedReceivable(context, receivableId); ensureBuyerScope(context, receivable.clientAccountId());
        BigDecimal amount = receivable.amount().subtract(receivable.amountPaid());
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        CreditRow credit = jdbc.query("select id,credit_limit,credit_exposure,reserved_exposure from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=? and status='ACTIVE' for update", (rs, n) -> new CreditRow(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)), tenant(context), workspace(context), receivable.clientAccountId(), receivable.currency()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Client credit account is not configured"));
        CreditAccount creditAccount = CreditAccount.rehydrate(credit.id().toString(), credit.limit(), credit.exposure(), credit.reserved());
        ExistingPayment existing = existingPayment(context, idempotencyKey);
        if (existing != null) {
            ensureIdempotentPayment(existing, receivable, PaymentMethod.CREDIT_LINE, amount);
            return paymentView(existing.payment(), PaymentMethod.CREDIT_LINE.name());
        }
        creditAccount.reserve(amount);
        UUID paymentId = UUID.randomUUID(); Instant now = Instant.now();
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure+?,version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and status='ACTIVE' and credit_exposure+reserved_exposure+?<=credit_limit", amount, Timestamp.from(now), tenant(context), workspace(context), credit.id(), amount) != 1) {
            throw new IllegalArgumentException("Credit limit exceeded");
        }
        int inserted = jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,idempotency_key,created_at,updated_at,completed_at) values (?,?,?,?,?,?, 'CREDIT_LINE','SUCCEEDED',?,?, 'NEXA_CREDIT',?,?,?,?) on conflict (tenant_id,workspace_id,created_by_membership_id,idempotency_key) do nothing", paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), amount, receivable.currency(), idempotencyKey, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) throw new IllegalArgumentException("Payment idempotency claim was lost");
        CreditReservation reservation = CreditReservation.reserve(UUID.randomUUID().toString(), amount);
        int reservationInserted = jdbc.update("insert into payments.credit_reservation (id,tenant_id,workspace_id,credit_account_id,receivable_id,payment_id,amount,status,idempotency_key,created_at) values (?,?,?,?,?,?,?,'RESERVED',?,?)", UUID.fromString(reservation.id()), tenant(context), workspace(context), credit.id(), receivable.id(), paymentId, reservation.amount(), idempotencyKey, Timestamp.from(now));
        if (reservationInserted != 1) throw new IllegalArgumentException("Credit reservation could not be persisted");
        reservation.consume();
        creditAccount.consumeReservation(amount);
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure-?,credit_exposure=credit_exposure+?,version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and reserved_exposure>=?", amount, amount, Timestamp.from(now), tenant(context), workspace(context), credit.id(), amount) != 1) {
            throw new IllegalArgumentException("Credit reservation could not be consumed");
        }
        jdbc.update("update payments.credit_reservation set status='CONSUMED' where tenant_id=? and workspace_id=? and id=? and status='RESERVED'", tenant(context), workspace(context), UUID.fromString(reservation.id()));
        applySucceededPayment(context, paymentId, receivable.id(), amount, receivable.currency(), "credit-line-" + paymentId);
        PaymentRow payment = new PaymentRow(paymentId, receivable.id(), PaymentStatus.SUCCEEDED.name(), amount, receivable.currency(), null, null, now, now, receivable.clientAccountId(), tenant(context), workspace(context));
        return paymentView(payment, PaymentMethod.CREDIT_LINE.name());
    }

    @Transactional
    public PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId, String idempotencyKey,
                                                        String transferReference, UUID proofEvidenceId) {
        context.requirePermission(PermissionKey.PAYMENT_CREATE); requireKey(idempotencyKey); lockIdempotencyKey(context, idempotencyKey);
        if (transferReference == null || transferReference.isBlank() || transferReference.length() > 160) throw new IllegalArgumentException("Bank transfer reference is required");
        ReceivableRow receivable = lockedReceivable(context, receivableId); ensureBuyerScope(context, receivable.clientAccountId()); BigDecimal amount = receivable.amount().subtract(receivable.amountPaid());
        if (amount.signum() <= 0 || !SetOfOpen.contains(receivable.status())) throw new IllegalArgumentException("Receivable is not payable");
        ExistingPayment existing = existingPayment(context, idempotencyKey);
        if (existing != null) {
            ensureIdempotentPayment(existing, receivable, PaymentMethod.BANK_TRANSFER, amount);
            return paymentView(existing.payment(), PaymentMethod.BANK_TRANSFER.name());
        }
        validateProofEvidence(context, receivable, proofEvidenceId);
        UUID paymentId = UUID.randomUUID(); Instant now = Instant.now();
        int inserted = jdbc.update("insert into payments.payment (id,tenant_id,workspace_id,client_account_id,receivable_id,created_by_membership_id,method,status,amount,currency,provider,idempotency_key,bank_transfer_reference,bank_transfer_proof_evidence_id,created_at,updated_at) values (?,?,?,?,?,?, 'BANK_TRANSFER','PROCESSING',?,?, 'BANK_TRANSFER',?,?,?,?,?) on conflict (tenant_id,workspace_id,created_by_membership_id,idempotency_key) do nothing", paymentId, tenant(context), workspace(context), receivable.clientAccountId(), receivable.id(), context.membershipId().value(), amount, receivable.currency(), idempotencyKey, transferReference.trim(), proofEvidenceId, Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) {
            ExistingPayment concurrent = existingPayment(context, idempotencyKey);
            if (concurrent == null) throw new IllegalArgumentException("Payment idempotency claim was lost");
            ensureIdempotentPayment(concurrent, receivable, PaymentMethod.BANK_TRANSFER, amount);
            return paymentView(concurrent.payment(), PaymentMethod.BANK_TRANSFER.name());
        }
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,created_at) values (?,?,?,?,1,'PROCESSING',?)", UUID.randomUUID(), tenant(context), workspace(context), paymentId, Timestamp.from(now));
        return paymentView(new PaymentRow(paymentId, receivable.id(), PaymentStatus.PROCESSING.name(), amount, receivable.currency(), null, null, now, null, receivable.clientAccountId(), tenant(context), workspace(context)), PaymentMethod.BANK_TRANSFER.name());
    }

    @Transactional
    public PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext context, UUID paymentId, String action,
                                                        String reason, String idempotencyKey) {
        context.requirePermission(PermissionKey.PAYMENT_RECONCILE); requireKey(idempotencyKey);
        PaymentRow payment = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=? and p.method='BANK_TRANSFER' for update", (rs, n) -> paymentRow(rs), tenant(context), workspace(context), paymentId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Bank transfer payment not found"));
        String previousReviewKey = jdbc.query("select review_idempotency_key from payments.payment where tenant_id=? and workspace_id=? and id=? and method='BANK_TRANSFER'", (rs, n) -> rs.getString(1), tenant(context), workspace(context), paymentId)
                .stream().filter(value -> value != null).findFirst().orElse(null);
        if (idempotencyKey.equals(previousReviewKey)) return paymentView(payment, "BANK_TRANSFER");
        if (!Set.of("PROCESSING", "FAILED").contains(payment.status())) throw new IllegalArgumentException("Bank transfer is not reviewable");
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if ("REJECT".equals(normalized)) {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Bank transfer rejection reason is required");
            jdbc.update("update payments.payment set status='FAILED',review_idempotency_key=?,review_action='REJECT',reviewed_by_membership_id=?,reviewed_at=current_timestamp,review_reason=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=?", idempotencyKey, context.membershipId().value(), reason.trim(), tenant(context), workspace(context), payment.id());
            jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,failure_code,failure_detail,created_at) select ?,tenant_id,workspace_id,id,coalesce((select max(attempt_number)+1 from payments.payment_attempt where payment_id=?),1),'FAILED','BANK_TRANSFER_REJECTED',?,current_timestamp from payments.payment where id=?", UUID.randomUUID(), payment.id(), reason.trim(), payment.id());
            return paymentView(new PaymentRow(payment.id(), payment.receivableId(), "FAILED", payment.amount(), payment.currency(), null, payment.providerId(), payment.createdAt(), null, payment.clientAccountId(), payment.tenantId(), payment.workspaceId()), "BANK_TRANSFER");
        }
        if (!Set.of("APPROVE", "RECONCILE").contains(normalized)) throw new IllegalArgumentException("Bank transfer review action is invalid");
        validateStoredProofEvidence(context, payment);
        applySucceededPaymentForStoredContext(payment, "bank-transfer-" + idempotencyKey);
        jdbc.update("update payments.payment set status='SUCCEEDED',review_idempotency_key=?,review_action=?,reviewed_by_membership_id=?,reviewed_at=current_timestamp,review_reason=?,updated_at=current_timestamp,completed_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=?", idempotencyKey, normalized, context.membershipId().value(), normalized, tenant(context), workspace(context), payment.id());
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
        jdbc.update("update payments.stripe_event_inbox set status='FAILED',failure_detail='Stale processing attempt',next_attempt_at=current_timestamp where status='PROCESSING' and received_at < current_timestamp - interval '10 minutes'");
        List<WebhookWork> work = jdbc.query("select event_id,tenant_id,workspace_id from payments.stripe_event_inbox where status in ('RECEIVED','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp order by received_at,event_id limit 20", (rs, n) -> new WebhookWork(rs.getString("event_id"), rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class)));
        for (WebhookWork item : work) {
            if (jdbc.update("update payments.stripe_event_inbox set status='PROCESSING',attempt_count=attempt_count+1,failure_detail=null where event_id=? and status in ('RECEIVED','FAILED') and attempt_count < 10 and next_attempt_at <= current_timestamp", item.eventId()) == 0) continue;
            try {
                if (item.tenantId() != null && item.workspaceId() != null) RlsRequestScope.set(item.tenantId(), item.workspaceId());
                transactionTemplate.executeWithoutResult(transaction -> {
                    processWebhook(item.eventId());
                    jdbc.update("update payments.stripe_event_inbox set status='PROCESSED',processed_at=current_timestamp,next_attempt_at=current_timestamp where event_id=?", item.eventId());
                });
            } catch (RuntimeException exception) {
                jdbc.update("update payments.stripe_event_inbox set status='FAILED',failure_detail=?,next_attempt_at=current_timestamp + (least(power(2,attempt_count),300) * interval '1 second') where event_id=?", truncate(exception.getMessage()), item.eventId());
            } finally {
                RlsRequestScope.clear();
            }
        }
    }

    @Transactional
    void processWebhook(String eventId) {
        StripeEventRow event = jdbc.query("select event_id,event_type,payment_intent_id,payment_status,amount_minor,currency,tenant_id,workspace_id from payments.stripe_event_inbox where event_id=?", (rs, n) -> new StripeEventRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, Long.class), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class)), eventId).stream().findFirst().orElseThrow();
        if (event.paymentIntentId() == null || !event.eventType().startsWith("payment_intent.")) { jdbc.update("update payments.stripe_event_inbox set status='IGNORED',processed_at=current_timestamp where event_id=?", eventId); return; }
        if (event.tenantId() == null || event.workspaceId() == null) throw new IllegalArgumentException("Stripe webhook tenant binding is missing");
        PaymentRow payment = jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.provider_payment_intent_id=? for update", (rs, n) -> paymentRow(rs), event.tenantId(), event.workspaceId(), event.paymentIntentId()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Stripe payment intent is not known"));
        if (event.amountMinor() != null && event.amountMinor() != minor(payment.amount(), payment.currency())) throw new IllegalArgumentException("Stripe amount does not match receivable payment");
        if (event.currency() != null && !event.currency().equalsIgnoreCase(payment.currency())) throw new IllegalArgumentException("Stripe currency does not match receivable payment");
        PaymentStatus next = statusFromEvent(event.eventType(), event.paymentStatus());
        if (next == null) { jdbc.update("update payments.stripe_event_inbox set status='IGNORED',processed_at=current_timestamp where event_id=?", eventId); return; }
        Payment aggregate = Payment.rehydrate(payment.id().toString(), payment.amount(), PaymentStatus.valueOf(payment.status()));
        final boolean changed;
        try {
            changed = aggregate.applyProviderStatus(next);
        } catch (IllegalArgumentException staleEvent) {
            jdbc.update("update payments.stripe_event_inbox set status='IGNORED',failure_detail=?,processed_at=current_timestamp where event_id=?", truncate(staleEvent.getMessage()), eventId);
            return;
        }
        if (!changed) {
            jdbc.update("update payments.stripe_event_inbox set status='IGNORED',processed_at=current_timestamp where event_id=?", eventId);
            return;
        }
        if (next == PaymentStatus.SUCCEEDED) applySucceededPaymentForStoredContext(payment, eventId);
        jdbc.update("update payments.payment set status=?,updated_at=current_timestamp,completed_at=case when ?='SUCCEEDED' then current_timestamp else completed_at end,version=version+1 where tenant_id=? and workspace_id=? and id=?", aggregate.status().name(), aggregate.status().name(), event.tenantId(), event.workspaceId(), payment.id());
        jdbc.update("insert into payments.payment_attempt (id,tenant_id,workspace_id,payment_id,attempt_number,status,provider_reference,failure_code,created_at) select ?,p.tenant_id,p.workspace_id,p.id,coalesce((select max(a.attempt_number)+1 from payments.payment_attempt a where a.payment_id=p.id),1),?,?,case when ?='FAILED' then 'PROVIDER_DECLINED' else null end,current_timestamp from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.id=?", UUID.randomUUID(), aggregate.status().name(), event.paymentIntentId(), aggregate.status().name(), event.tenantId(), event.workspaceId(), payment.id());
    }

    private void applySucceededPaymentForStoredContext(PaymentRow payment, String eventKey) {
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update", (rs, n) -> receivableRow(rs), payment.tenantId(), payment.workspaceId(), payment.receivableId()).stream().findFirst().orElseThrow();
        if (!receivable.currency().equalsIgnoreCase(payment.currency())) throw new IllegalArgumentException("Payment currency does not match receivable");
        Receivable aggregate = receivableAggregate(receivable);
        aggregate.allocate(payment.amount());
        ReceivableAllocation allocation = new ReceivableAllocation(UUID.randomUUID().toString(), payment.id().toString(), payment.amount());
        int inserted = jdbc.update("insert into payments.receivable_allocation (id,tenant_id,workspace_id,receivable_id,payment_id,amount,allocated_at) values (?,?,?,?,?,?,current_timestamp) on conflict (payment_id) do nothing", UUID.fromString(allocation.id()), tenant(payment), workspace(payment), receivable.id(), UUID.fromString(allocation.paymentId()), allocation.amount());
        if (inserted == 0) return;
        if (jdbc.update("update payments.receivable set amount_paid=?,status=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", aggregate.amountPaid(), aggregate.status().name(), payment.tenantId(), payment.workspaceId(), receivable.id(), receivable.version()) != 1) {
            throw new IllegalArgumentException("Receivable changed while applying payment");
        }
        jdbc.update("insert into payments.payment_event (id,tenant_id,workspace_id,payment_id,event_type,event_key,occurred_at) values (?,?,?,?,?,?,current_timestamp) on conflict (payment_id,event_key) do nothing", UUID.randomUUID(), tenant(payment), workspace(payment), payment.id(), "PAYMENT_SUCCEEDED", eventKey);
        enqueuePaymentReceipt(payment, receivable, eventKey);
        outbox(payment, "PAYMENT_SUCCEEDED", Map.of("paymentId", payment.id(), "receivableId", receivable.id(), "amount", payment.amount(), "currency", payment.currency()));
    }

    private void applySucceededPayment(CurrentAccessContext context, UUID paymentId, UUID receivableId, BigDecimal amount, String currency, String eventKey) {
        PaymentRow payment = new PaymentRow(paymentId, receivableId, "SUCCEEDED", amount, currency, null, null, Instant.now(), Instant.now(), null, tenant(context), workspace(context));
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update", (rs, n) -> receivableRow(rs), tenant(context), workspace(context), receivableId).stream().findFirst().orElseThrow();
        if (!receivable.currency().equalsIgnoreCase(currency)) throw new IllegalArgumentException("Payment currency does not match receivable");
        Receivable aggregate = receivableAggregate(receivable);
        aggregate.allocate(amount);
        ReceivableAllocation allocation = new ReceivableAllocation(UUID.randomUUID().toString(), paymentId.toString(), amount);
        int inserted = jdbc.update("insert into payments.receivable_allocation (id,tenant_id,workspace_id,receivable_id,payment_id,amount,allocated_at) values (?,?,?,?,?,?,current_timestamp) on conflict (payment_id) do nothing", UUID.fromString(allocation.id()), tenant(context), workspace(context), receivableId, UUID.fromString(allocation.paymentId()), allocation.amount());
        if (inserted == 0) return;
        if (jdbc.update("update payments.receivable set amount_paid=?,status=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", aggregate.amountPaid(), aggregate.status().name(), tenant(context), workspace(context), receivableId, receivable.version()) != 1) {
            throw new IllegalArgumentException("Receivable changed while applying payment");
        }
        enqueuePaymentReceipt(payment, receivable, eventKey);
        outbox(payment, "PAYMENT_SUCCEEDED", Map.of("paymentId", paymentId, "receivableId", receivableId, "amount", amount, "currency", currency));
    }

    private void enqueuePaymentReceipt(PaymentRow payment, ReceivableRow receivable, String eventKey) {
        UUID documentId = UUID.randomUUID(); UUID requestId = UUID.randomUUID(); Instant now = Instant.now();
        Integer version = jdbc.queryForObject("select coalesce(max(version),0)+1 from business_documents.business_document where tenant_id=? and workspace_id=? and subject_type='PAYMENT' and subject_id=? and document_type='PAYMENT_RECEIPT' and format='PDF'", Integer.class, tenant(payment), workspace(payment), payment.id());
        int inserted = jdbc.update("insert into business_documents.business_document (id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,document_type,version,status,format,created_at,updated_at) values (?,?,?,?, 'PAYMENT',?,'PAYMENT_RECEIPT',?,'REQUESTED','PDF',?,?) on conflict do nothing", documentId, tenant(payment), workspace(payment), receivable.clientAccountId(), payment.id(), version, Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) return;
        UUID requester = jdbc.queryForObject("select created_by_membership_id from payments.payment where tenant_id=? and workspace_id=? and id=?", UUID.class, tenant(payment), workspace(payment), payment.id());
        jdbc.update("insert into business_documents.document_generation_request (id,tenant_id,workspace_id,requested_by_membership_id,document_id,subject_type,subject_id,document_type,format,status,idempotency_key,request_hash,requested_at) values (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?) on conflict do nothing", requestId, tenant(payment), workspace(payment), requester, documentId, "PAYMENT", payment.id(), "PAYMENT_RECEIPT", "PDF", "payment-receipt-" + payment.id(), sha256(eventKey), Timestamp.from(now));
    }

    private void outbox(PaymentRow payment, String eventType, Map<String, Object> payload) { CanonicalOutbox.append(jdbc, eventType, "Payment", payment.id(), tenant(payment), workspace(payment), Instant.now(), "payment-" + payment.id(), null, "1.0", payload); }
    private String json(Map<?, ?> payload) { try { return objectMapper.writeValueAsString(payload); } catch (Exception exception) { throw new IllegalStateException("Payment JSON serialization failed", exception); } }

    private PaymentModels.ReceivableView receivableView(ReceivableRow row) {
        Receivable aggregate = receivableAggregate(row);
        return new PaymentModels.ReceivableView(row.id().toString(), row.clientAccountId().toString(), row.subjectType(), row.subjectId().toString(), row.number(), row.currency(), aggregate.amount(), aggregate.amountPaid(), aggregate.remaining(), aggregate.status().name(), row.dueAt(), row.version());
    }
    private PaymentModels.PaymentIntentView paymentIntentView(PaymentRow row, String clientSecret) {
        Payment aggregate = paymentAggregate(row);
        return new PaymentModels.PaymentIntentView(row.id().toString(), row.receivableId().toString(), aggregate.status().name(), aggregate.amount(), row.currency(), clientSecret, publishableKey, row.providerId(), row.createdAt());
    }
    private PaymentModels.PaymentView paymentView(PaymentRow row, String method) {
        Payment aggregate = paymentAggregate(row);
        return new PaymentModels.PaymentView(row.id().toString(), row.receivableId().toString(), method, aggregate.status().name(), aggregate.amount(), row.currency(), row.createdAt(), row.completedAt());
    }
    private Receivable receivableAggregate(ReceivableRow row) {
        return Receivable.rehydrate(row.id().toString(), row.amount(), row.amountPaid(), ReceivableStatus.valueOf(row.status()));
    }
    private Payment paymentAggregate(PaymentRow row) {
        return Payment.rehydrate(row.id().toString(), row.amount(), PaymentStatus.valueOf(row.status()));
    }
    private ExistingPayment existingPayment(CurrentAccessContext context, String idempotencyKey) {
        return jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.receivable_id,p.status,p.amount,p.currency,p.provider_payment_intent_id,p.created_at,p.completed_at,p.client_account_id,p.method from payments.payment p where p.tenant_id=? and p.workspace_id=? and p.created_by_membership_id=? and p.idempotency_key=?", (rs, n) -> new ExistingPayment(paymentRow(rs), rs.getString("method")), tenant(context), workspace(context), context.membershipId().value(), idempotencyKey).stream().findFirst().orElse(null);
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
    private List<ReceivableRow> receivableQuery(CurrentAccessContext c, UUID id) { return jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=?", (rs, n) -> receivableRow(rs), tenant(c), workspace(c), id); }
    private List<ReceivableRow> receivableQuery(CurrentAccessContext c, UUID subjectId, String subjectType) { return jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and subject_id=? and subject_type=?", (rs, n) -> receivableRow(rs), tenant(c), workspace(c), subjectId, subjectType); }
    private ReceivableRow lockedReceivable(CurrentAccessContext c, UUID id) { return jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=? for update", (rs, n) -> receivableRow(rs), tenant(c), workspace(c), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Receivable not found")); }
    private PaymentRow paymentRow(java.sql.ResultSet rs) throws java.sql.SQLException { return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("receivable_id", UUID.class), rs.getString("status"), rs.getBigDecimal("amount"), rs.getString("currency"), null, rs.getString("provider_payment_intent_id"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(), rs.getObject("client_account_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class)); }
    private ReceivableRow receivableRow(java.sql.ResultSet rs) throws java.sql.SQLException { return new ReceivableRow(rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("receivable_number"), rs.getString("currency"), rs.getBigDecimal("amount"), rs.getBigDecimal("amount_paid"), rs.getString("status"), rs.getTimestamp("due_at").toInstant(), rs.getLong("version")); }
    private AuthoritativeSubject authoritativeSubject(CurrentAccessContext context, String subjectType, UUID subjectId) {
        if (!"SALES_ORDER".equals(subjectType)) throw new IllegalArgumentException("Receivable subject must be a confirmed Sales Order");
        return jdbc.query("select client_account_id,total_amount,currency,status from sales.sales_order where tenant_id=? and workspace_id=? and id=?", (rs, n) -> new AuthoritativeSubject(rs.getObject("client_account_id", UUID.class), rs.getBigDecimal("total_amount"), rs.getString("currency"), rs.getString("status")), tenant(context), workspace(context), subjectId)
                .stream().findFirst().filter(value -> "CONFIRMED".equals(value.status()) && value.clientAccountId() != null && value.amount() != null && value.amount().signum() > 0 && value.currency() != null && !value.currency().isBlank())
                .map(value -> new AuthoritativeSubject(value.clientAccountId(), value.amount(), value.currency().trim().toUpperCase(Locale.ROOT), value.status()))
                .orElseThrow(() -> new IllegalArgumentException("Sales Order is not a confirmed payable subject"));
    }
    private boolean authorizedClient(CurrentAccessContext c, UUID clientAccountId) { return !c.hasRole(MembershipRole.BUYER) || Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.client_account_membership where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=?)", Boolean.class, tenant(c), workspace(c), clientAccountId, c.membershipId().value())); }
    private void ensureBuyerScope(CurrentAccessContext c, UUID clientAccountId) { if (!authorizedClient(c, clientAccountId)) throw new IllegalArgumentException("Receivable is outside buyer scope"); }
    private void validateProofEvidence(CurrentAccessContext context, ReceivableRow receivable, UUID evidenceId) {
        if (evidenceId == null) throw new IllegalArgumentException("Bank transfer proof evidence is required");
        Boolean available = jdbc.queryForObject("select exists(select 1 from business_documents.evidence_object where tenant_id=? and workspace_id=? and id=? and client_account_id=? and lifecycle_status='AVAILABLE' and ((subject_type='RECEIVABLE' and subject_id=?) or (subject_type=? and subject_id=?)))", Boolean.class, tenant(context), workspace(context), evidenceId, receivable.clientAccountId(), receivable.id(), receivable.subjectType(), receivable.subjectId());
        if (!Boolean.TRUE.equals(available)) throw new IllegalArgumentException("Bank transfer proof evidence is not available or is bound to another subject");
    }

    private void validateStoredProofEvidence(CurrentAccessContext context, PaymentRow payment) {
        UUID evidenceId = jdbc.queryForObject("select bank_transfer_proof_evidence_id from payments.payment where tenant_id=? and workspace_id=? and id=?", UUID.class, tenant(context), workspace(context), payment.id());
        ReceivableRow receivable = jdbc.query("select id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,amount_paid,status,due_at,version from payments.receivable where tenant_id=? and workspace_id=? and id=?", (rs, n) -> receivableRow(rs), tenant(context), workspace(context), payment.receivableId()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Receivable not found"));
        validateProofEvidence(context, receivable, evidenceId);
    }
    private void ensureCreditAccount(CurrentAccessContext c, UUID clientAccountId, String currency, Instant now) { jdbc.update("insert into payments.credit_account (id,tenant_id,workspace_id,client_account_id,currency,credit_limit,created_at,updated_at) values (?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,client_account_id,currency) do nothing", UUID.randomUUID(), tenant(c), workspace(c), clientAccountId, currency, BigDecimal.ZERO, Timestamp.from(now), Timestamp.from(now)); }
    private static PaymentStatus providerStatus(String value) { if (value == null) return PaymentStatus.REQUIRES_ACTION; return switch (value.toLowerCase(Locale.ROOT)) { case "succeeded" -> PaymentStatus.SUCCEEDED; case "processing" -> PaymentStatus.PROCESSING; case "canceled", "cancelled" -> PaymentStatus.CANCELLED; case "requires_payment_method", "requires_action" -> PaymentStatus.REQUIRES_ACTION; default -> PaymentStatus.CREATED; }; }
    private static PaymentStatus statusFromEvent(String type, String providerStatus) { if ("payment_intent.succeeded".equals(type)) return PaymentStatus.SUCCEEDED; if ("payment_intent.payment_failed".equals(type)) return PaymentStatus.FAILED; if ("payment_intent.processing".equals(type)) return PaymentStatus.PROCESSING; if ("payment_intent.canceled".equals(type)) return PaymentStatus.CANCELLED; return null; }
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
    private record StripeEventRow(String id, String eventType, String paymentIntentId, String paymentStatus, Long amountMinor, String currency, UUID tenantId, UUID workspaceId) { }
    private record ExistingPayment(PaymentRow payment, String method) { }
    private record WebhookWork(String eventId, UUID tenantId, UUID workspaceId) { }
    private record PaymentViewRow(PaymentRow payment, String method) { }
    private record AuthoritativeSubject(UUID clientAccountId, BigDecimal amount, String currency, String status) { }
    private record PaymentRow(UUID id, UUID receivableId, String status, BigDecimal amount, String currency, String clientSecret, String providerId, Instant createdAt, Instant completedAt, UUID clientAccountId, UUID tenantId, UUID workspaceId) {
        private PaymentRow(UUID id, UUID receivableId, String status, BigDecimal amount, String currency, String clientSecret, String providerId, Instant createdAt, Instant completedAt, UUID clientAccountId) { this(id, receivableId, status, amount, currency, clientSecret, providerId, createdAt, completedAt, clientAccountId, null, null); }
    }
}
