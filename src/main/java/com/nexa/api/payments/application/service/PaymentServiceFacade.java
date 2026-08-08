package com.nexa.api.payments.application.service;

import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.port.PaymentPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@Profile("!test")
public class PaymentServiceFacade {
    private final PaymentPort port;
    public PaymentServiceFacade(PaymentPort port) { this.port = port; }
    public PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(CurrentAccessContext c, int page, int size) { return port.listReceivables(c, page, size); }
    public PaymentModels.ReceivableView getReceivable(CurrentAccessContext c, UUID id) { return port.getReceivable(c, id); }
    public PaymentModels.PaymentView getPayment(CurrentAccessContext c, UUID id) { return port.getPayment(c, id); }
    public PaymentModels.ReceivableView createReceivable(CurrentAccessContext c, ReceivableRequest r) { return port.createReceivable(c, new PaymentPort.ReceivableCommand(r.subjectType(), r.subjectId(), r.dueAt(), r.idempotencyKey())); }
    public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext c, UUID id, String key) { return port.createCardPaymentIntent(c, id, key); }
    public PaymentModels.PaymentView confirmTestCardPayment(CurrentAccessContext c, UUID receivableId, String clientSecret) { return port.confirmTestCardPayment(c, receivableId, clientSecret); }
    public PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext c, UUID id, String key) { return port.createCreditLinePayment(c, id, key); }
    public PaymentModels.PaymentView createBankTransfer(CurrentAccessContext c, UUID id, String key, String reference, UUID proofEvidenceId) { return port.createBankTransfer(c, id, key, reference, proofEvidenceId); }
    public PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext c, UUID id, String action, String reason, String key) { return port.reviewBankTransfer(c, id, action, reason, key); }
    public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) { return port.receiveStripeWebhook(payload, signature); }
    public record ReceivableRequest(String subjectType, UUID subjectId, Instant dueAt, String idempotencyKey) { }
}
