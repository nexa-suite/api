package com.nexa.api.payments.application.publicapi.webhooks;

import com.nexa.api.payments.application.publicapi.model.PaymentModels;

/** Trusted webhook controller boundary; signature verification and inbox policy stay in Payments. */
public interface PaymentWebhookIntake {
    PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature);
}
