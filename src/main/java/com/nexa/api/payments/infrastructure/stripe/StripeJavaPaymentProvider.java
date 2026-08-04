package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Official Stripe adapter. Secrets remain server-side and card data never enters Nexa. */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.payments", name = "provider", havingValue = "stripe")
public final class StripeJavaPaymentProvider implements StripePaymentProvider {
    private final String secretKey;
    private final String webhookSecret;

    public StripeJavaPaymentProvider(
            @Value("${nexa.payments.secret-key:}") String secretKey,
            @Value("${nexa.payments.webhook-secret:}") String webhookSecret) {
        if (secretKey == null || secretKey.isBlank() || webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("Stripe secret and webhook secret are required for the stripe provider");
        }
        this.secretKey = secretKey.trim(); this.webhookSecret = webhookSecret.trim();
    }

    @Override
    public StripePaymentProvider.PaymentIntent createPaymentIntent(PaymentIntentRequest request) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(request.amountMinor())
                    .setCurrency(request.currency().toLowerCase(Locale.ROOT))
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
                    .putAllMetadata(request.metadata())
                    .build();
            RequestOptions options = RequestOptions.builder().setApiKey(secretKey).setIdempotencyKey(request.idempotencyKey()).build();
            com.stripe.model.PaymentIntent intent = com.stripe.model.PaymentIntent.create(params, options);
            return new StripePaymentProvider.PaymentIntent(intent.getId(), intent.getClientSecret(), intent.getStatus());
        } catch (Exception exception) {
            throw new IllegalStateException("Stripe PaymentIntent creation failed", exception);
        }
    }

    @Override
    public Optional<StripePaymentProvider.PaymentIntent> retrievePaymentIntent(String providerId) {
        if (providerId == null || providerId.isBlank()) return Optional.empty();
        try {
            RequestOptions options = RequestOptions.builder().setApiKey(secretKey).build();
            com.stripe.model.PaymentIntent intent = com.stripe.model.PaymentIntent.retrieve(providerId, options);
            return Optional.of(new StripePaymentProvider.PaymentIntent(intent.getId(), intent.getClientSecret(), intent.getStatus()));
        } catch (Exception exception) {
            throw new IllegalStateException("Stripe PaymentIntent retrieval failed", exception);
        }
    }

    @Override
    public StripeWebhookEvent verifyWebhook(String payload, String signature) {
        try {
            Event event = Webhook.constructEvent(payload, signature, webhookSecret);
            var deserializer = event.getDataObjectDeserializer();
            StripeObject object = deserializer.getObject().orElse(null);
            if (object == null) {
                /* A signed event can legitimately carry a newer Stripe API
                 * version than the SDK. The unsafe path still deserializes
                 * the verified raw object without accepting an unverified body. */
                object = deserializer.deserializeUnsafe();
            }
            if (object instanceof com.stripe.model.PaymentIntent intent) {
                Map<String, String> metadata = intent.getMetadata() == null ? Map.of() : intent.getMetadata();
                return new StripeWebhookEvent(event.getId(), event.getType(), intent.getId(), intent.getStatus(), intent.getAmount(), intent.getCurrency(), metadata);
            }
            return new StripeWebhookEvent(event.getId(), event.getType(), null, null, null, null, Map.of());
        } catch (SignatureVerificationException exception) {
            throw new IllegalArgumentException("Stripe webhook signature is invalid", exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Stripe webhook payload is invalid", exception);
        }
    }
}
