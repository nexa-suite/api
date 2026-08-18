package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.shared.infrastructure.observability.TechnicalMetrics;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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
    private final String apiBaseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxNetworkRetries;
    private final StripeClient client;
    private final TechnicalMetrics metrics;

    @Autowired
    public StripeJavaPaymentProvider(
            @Value("${nexa.payments.secret-key:}") String secretKey,
            @Value("${nexa.payments.webhook-secret:}") String webhookSecret,
            @Value("${nexa.payments.api-base-url:}") String apiBaseUrl,
            @Value("${nexa.payments.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${nexa.payments.read-timeout-ms:10000}") int readTimeoutMs,
            @Value("${nexa.payments.max-network-retries:0}") int maxNetworkRetries,
            ObjectProvider<TechnicalMetrics> metrics) {
        this(secretKey, webhookSecret, apiBaseUrl, connectTimeoutMs, readTimeoutMs, maxNetworkRetries,
                metrics == null ? null : metrics.getIfAvailable());
    }

    public StripeJavaPaymentProvider(String secretKey, String webhookSecret) {
        this(secretKey, webhookSecret, "", 5000, 10000, 0, (TechnicalMetrics) null);
    }

    public StripeJavaPaymentProvider(String secretKey, String webhookSecret, String apiBaseUrl) {
        this(secretKey, webhookSecret, apiBaseUrl, 5000, 10000, 0, (TechnicalMetrics) null);
    }

    private StripeJavaPaymentProvider(String secretKey, String webhookSecret, String apiBaseUrl,
                                      int connectTimeoutMs, int readTimeoutMs, int maxNetworkRetries,
                                      TechnicalMetrics metrics) {
        if (secretKey == null || secretKey.isBlank() || webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("Stripe secret and webhook secret are required for the stripe provider");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || maxNetworkRetries < 0) {
            throw new IllegalStateException("Stripe timeout values must be positive and max network retries cannot be negative");
        }
        this.secretKey = secretKey.trim();
        this.webhookSecret = webhookSecret.trim();
        this.apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxNetworkRetries = maxNetworkRetries;
        var builder = StripeClient.builder()
                .setApiKey(this.secretKey)
                .setConnectTimeout(connectTimeoutMs)
                .setReadTimeout(readTimeoutMs)
                .setMaxNetworkRetries(maxNetworkRetries);
        if (!this.apiBaseUrl.isBlank()) builder.setApiBase(this.apiBaseUrl);
        this.client = builder.build();
        this.metrics = metrics;
    }

    @Override
    public StripePaymentProvider.PaymentIntent createPaymentIntent(PaymentIntentRequest request) {
        TechnicalMetrics.TimerSample timer = start("create_payment_intent");
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(request.amountMinor())
                    .setCurrency(request.currency().toLowerCase(Locale.ROOT))
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
                    .putAllMetadata(request.metadata())
                    .build();
            com.stripe.model.PaymentIntent intent = client.paymentIntents().create(params, options(request.idempotencyKey()));
            stop(timer, "success");
            return new StripePaymentProvider.PaymentIntent(intent.getId(), intent.getClientSecret(), intent.getStatus());
        } catch (StripeException exception) {
            stop(timer, category(exception));
            throw translate("create", exception);
        }
    }

    @Override
    public Optional<StripePaymentProvider.PaymentIntent> retrievePaymentIntent(String providerId) {
        if (providerId == null || providerId.isBlank()) return Optional.empty();
        TechnicalMetrics.TimerSample timer = start("retrieve_payment_intent");
        try {
            com.stripe.model.PaymentIntent intent = client.paymentIntents().retrieve(providerId, options(null));
            stop(timer, "success");
            return Optional.of(new StripePaymentProvider.PaymentIntent(intent.getId(), intent.getClientSecret(), intent.getStatus()));
        } catch (StripeException exception) {
            stop(timer, category(exception));
            throw translate("retrieve", exception);
        }
    }

    @Override
    public StripePaymentProvider.PaymentIntent confirmPaymentIntent(String providerId) {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("Stripe PaymentIntent id is required");
        TechnicalMetrics.TimerSample timer = start("confirm_payment_intent");
        try {
            com.stripe.model.PaymentIntent confirmed = client.paymentIntents().confirm(providerId, options(null));
            stop(timer, "success");
            return new StripePaymentProvider.PaymentIntent(confirmed.getId(), confirmed.getClientSecret(), confirmed.getStatus());
        } catch (StripeException exception) {
            stop(timer, category(exception));
            throw translate("confirm", exception);
        }
    }

    @Override
    public StripeWebhookEvent verifyWebhook(String payload, String signature) {
        try {
            Event event = client.constructEvent(payload, signature, webhookSecret);
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

    private RequestOptions options(String idempotencyKey) {
        var builder = RequestOptions.builder();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) builder.setIdempotencyKey(idempotencyKey);
        return builder.build();
    }

    private TechnicalFailureException translate(String operation, StripeException exception) {
        TechnicalFailureException.Kind kind;
        if (exception instanceof ApiConnectionException) {
            kind = TechnicalFailureException.Kind.EXTERNAL_TIMEOUT;
        } else if (exception instanceof RateLimitException) {
            kind = TechnicalFailureException.Kind.EXTERNAL_TEMPORARY_FAILURE;
        } else if (exception instanceof AuthenticationException) {
            kind = TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE;
        } else {
            Integer status = exception.getStatusCode();
            kind = status != null && (status == 408 || status == 504)
                    ? TechnicalFailureException.Kind.EXTERNAL_TIMEOUT
                    : status != null && status >= 500
                    ? TechnicalFailureException.Kind.EXTERNAL_TEMPORARY_FAILURE
                    : TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE;
        }
        String requestId = exception.getRequestId();
        String safeRequestId = requestId == null || requestId.isBlank() ? null : requestId;
        return new TechnicalFailureException(kind, "Stripe " + operation + " request failed", exception, safeRequestId);
    }

    private String category(StripeException exception) {
        return exception instanceof ApiConnectionException ? "timeout"
                : exception instanceof RateLimitException ? "rate_limit"
                : exception instanceof AuthenticationException ? "authentication"
                : exception.getStatusCode() != null && exception.getStatusCode() >= 500 ? "provider"
                : "request";
    }

    private TechnicalMetrics.TimerSample start(String operation) {
        return metrics == null ? null : metrics.start("stripe", operation);
    }

    private void stop(TechnicalMetrics.TimerSample timer, String outcome) {
        if (timer != null) timer.stop(outcome);
    }
}
