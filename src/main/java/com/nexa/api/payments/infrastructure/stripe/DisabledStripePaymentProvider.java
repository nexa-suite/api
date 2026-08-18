package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;

import java.util.Optional;

/** Explicit non-provider adapter for deployments that do not enable Payments. */
public final class DisabledStripePaymentProvider implements StripePaymentProvider {
    private static final String MESSAGE = "Payment provider is disabled; configure NEXA_PAYMENTS_PROVIDER=stripe to enable Stripe";

    @Override
    public PaymentIntent createPaymentIntent(PaymentIntentRequest request) { throw unavailable(); }

    @Override
    public Optional<PaymentIntent> retrievePaymentIntent(String providerId) { throw unavailable(); }

    @Override
    public PaymentIntent confirmPaymentIntent(String providerId) { throw unavailable(); }

    @Override
    public StripeWebhookEvent verifyWebhook(String payload, String signature) { throw unavailable(); }

    private static IllegalStateException unavailable() { return new IllegalStateException(MESSAGE); }
}
