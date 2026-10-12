package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.application.publicapi.webhooks.PaymentWebhookSignatureVerifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Exposes only the verified callback facts needed by Bootstrap routing. */
@Component
@Profile("!test")
public final class StripePaymentWebhookSignatureVerifierAdapter implements PaymentWebhookSignatureVerifier {
    private static final String PAYMENT_ROUTE = "nexa_payment_route_id";
    private static final String WALLET_RECHARGE = "nexa_wallet_recharge_id";

    private final StripePaymentProvider provider;

    public StripePaymentWebhookSignatureVerifierAdapter(StripePaymentProvider provider) {
        this.provider = Objects.requireNonNull(provider);
    }

    @Override
    public VerifiedEvent verify(String payload, String signature) {
        StripePaymentProvider.StripeWebhookEvent event = provider.verifyWebhook(payload, signature);
        return event == null ? null : new VerifiedEvent(event.eventId(), event.eventType(), event.paymentIntentId(),
                event.paymentStatus(), event.amountMinor(), event.currency(), event.metadata().get(PAYMENT_ROUTE),
                event.metadata().get(WALLET_RECHARGE));
    }
}
