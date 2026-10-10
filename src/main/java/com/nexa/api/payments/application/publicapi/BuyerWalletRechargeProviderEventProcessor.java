package com.nexa.api.payments.application.publicapi;

import java.util.UUID;

/** Dispatches only signature-verified, durably inboxed Stripe wallet-recharge events. */
public interface BuyerWalletRechargeProviderEventProcessor {
    Outcome process(VerifiedEvent event);

    enum Outcome { PROCESSED, CANCELLED, IGNORED, REJECTED }

    record VerifiedEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                        Long amountMinor, String currency, UUID tenantId, UUID workspaceId,
                        UUID rechargeId) { }
}
