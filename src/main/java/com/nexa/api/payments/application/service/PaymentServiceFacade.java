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
    public PaymentModels.ReceivableView getReceivable(CurrentAccessContext c, UUID id) { return port.getReceivable(c, id); }
    public PaymentModels.PaymentView getPayment(CurrentAccessContext c, UUID id) { return port.getPayment(c, id); }
    public PaymentModels.ReceivableView createReceivable(CurrentAccessContext c, ReceivableRequest r) { return port.createReceivable(c, new PaymentPort.ReceivableCommand(r.clientAccountId(), r.subjectType(), r.subjectId(), r.amount(), r.currency(), r.dueAt())); }
    public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext c, UUID id, String key) { return port.createCardPaymentIntent(c, id, key); }
    public PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext c, UUID id, String key) { return port.createCreditLinePayment(c, id, key); }
    public PaymentModels.PaymentView createBankTransfer(CurrentAccessContext c, UUID id, String key) { return port.createBankTransfer(c, id, key); }
    public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) { return port.receiveStripeWebhook(payload, signature); }
    public record ReceivableRequest(UUID clientAccountId, String subjectType, UUID subjectId, BigDecimal amount, String currency, Instant dueAt) { }
}
