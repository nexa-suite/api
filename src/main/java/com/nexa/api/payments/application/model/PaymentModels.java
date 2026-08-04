package com.nexa.api.payments.application.model;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentModels {
    private PaymentModels() { }

    public record ReceivableView(String id, String clientAccountId, String subjectType, String subjectId,
                                 String number, String currency, BigDecimal amount, BigDecimal amountPaid,
                                 BigDecimal remaining, String status, Instant dueAt, long version) { }

    public record PaymentIntentView(String paymentId, String receivableId, String status, BigDecimal amount,
                                    String currency, String clientSecret, String publishableKey,
                                    String providerPaymentIntentId, Instant createdAt) { }

    public record PaymentView(String id, String receivableId, String method, String status, BigDecimal amount,
                              String currency, String providerPaymentIntentId, Instant createdAt, Instant completedAt) { }

    public record WebhookReceipt(String eventId, String status) { }
}
