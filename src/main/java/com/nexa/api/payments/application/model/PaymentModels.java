package com.nexa.api.payments.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PaymentModels {
    private PaymentModels() { }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items); }
    }

    public record ReceivableView(String id, String clientAccountId, String subjectType, String subjectId,
                                 String number, String currency, BigDecimal amount, BigDecimal amountPaid,
                                 BigDecimal remaining, String status, Instant dueAt, long version) { }

    public record PaymentIntentView(String paymentId, String receivableId, String status, BigDecimal amount,
                                    String currency, String clientSecret, String publishableKey,
                                    String providerPaymentIntentId, Instant createdAt) { }

    public record PaymentView(String id, String receivableId, String method, String status, BigDecimal amount,
                              String currency, Instant createdAt, Instant completedAt) { }

    public record PaymentSummaryView(String id, String receivableId, String receivableNumber, String clientAccountId,
                                     String method, String status, BigDecimal amount, String currency,
                                     String reference, String reviewReason, Instant createdAt, Instant completedAt) { }

    public record ReconciliationCaseView(String id, String paymentId, String receivableId, String salesOrderId,
                                         String allocationStatus, String state, String providerRefundId,
                                         int attemptCount, String lastError, String operatorNote,
                                         Instant createdAt, Instant updatedAt, Instant resolvedAt) { }

    public record WebhookReceipt(String eventId, String status) { }
}
