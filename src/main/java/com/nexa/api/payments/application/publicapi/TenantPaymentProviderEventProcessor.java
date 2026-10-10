package com.nexa.api.payments.application.publicapi;

import java.util.UUID;

/** Applies one signature-verified Stripe event using its central opaque Tenant payment route. */
public interface TenantPaymentProviderEventProcessor {
    Outcome process(VerifiedEvent event);

    enum Outcome { PROCESSED, IGNORED }

    record VerifiedEvent(String eventId, String eventType, String providerPaymentIntentId,
                         String providerStatus, Long amountMinor, String currency, UUID routeId) { }
}
