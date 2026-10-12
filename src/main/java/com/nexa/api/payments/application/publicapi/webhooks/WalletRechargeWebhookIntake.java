package com.nexa.api.payments.application.publicapi.webhooks;

import com.nexa.api.payments.application.publicapi.model.PaymentModels;

/** Re-verifies and records the existing wallet-recharge callback in the central Payments inbox. */
public interface WalletRechargeWebhookIntake {
    PaymentModels.WebhookReceipt receiveWalletRechargeWebhook(String payload, String signature);
}
