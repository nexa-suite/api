package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local-only provider for reproducible acceptance; it is not registered outside the local profile. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.payments", name = "provider", havingValue = "deterministic", matchIfMissing = true)
public final class DeterministicLocalStripePaymentProvider implements StripePaymentProvider {
    private final String webhookSecret;
    private final Map<String, PaymentIntent> intents = new ConcurrentHashMap<>();

    public DeterministicLocalStripePaymentProvider(@Value("${nexa.payments.webhook-secret:whsec_local_service_foundation}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override
    public PaymentIntent createPaymentIntent(PaymentIntentRequest request) {
        String id = "pi_local_" + UUID.nameUUIDFromBytes(request.idempotencyKey().getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        PaymentIntent intent = new PaymentIntent(id, id + "_secret_local", "REQUIRES_ACTION");
        intents.putIfAbsent(id, intent);
        return intent;
    }

    @Override
    public Optional<PaymentIntent> retrievePaymentIntent(String providerId) { return Optional.ofNullable(intents.get(providerId)); }

    @Override
    public StripeWebhookEvent verifyWebhook(String payload, String signature) {
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("Stripe webhook signature is required");
        String timestamp = value(signature, "t"); String provided = value(signature, "v1");
        long signedAt;
        try { signedAt = Long.parseLong(timestamp); } catch (RuntimeException exception) { throw new IllegalArgumentException("Stripe webhook timestamp is invalid", exception); }
        if (Math.abs(Instant.now().getEpochSecond() - signedAt) > 300) throw new IllegalArgumentException("Stripe webhook timestamp is outside tolerance");
        String expected = hmac(timestamp + "." + payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException("Stripe webhook signature is invalid");
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "nexa_tenant_id", first(payload, "nexa_tenant_id", null));
        putIfPresent(metadata, "nexa_workspace_id", first(payload, "nexa_workspace_id", null));
        return new StripeWebhookEvent(first(payload, "id", null), first(payload, "type", null), first(payload, "payment_intent_id", first(payload, "object_id", null)), first(payload, "status", null), number(payload, "amount"), optionalUpper(first(payload, "currency", null)), metadata);
    }

    private String hmac(String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Local Stripe webhook secret is invalid", exception); }
    }
    private static String value(String signature, String name) { for (String part : signature.split(",")) { String[] pair = part.split("=", 2); if (pair.length == 2 && pair[0].trim().equals(name)) return pair[1].trim(); } throw new IllegalArgumentException("Stripe webhook signature is incomplete"); }
    private static String first(String payload, String name, String fallback) { Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(payload); return matcher.find() ? matcher.group(1) : fallback; }
    private static Long number(String payload, String name) { Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*(\\d+)").matcher(payload); return matcher.find() ? Long.valueOf(matcher.group(1)) : null; }
    private static String optionalUpper(String value) { return value == null ? null : value.toUpperCase(Locale.ROOT); }
    private static void putIfPresent(Map<String, String> metadata, String key, String value) { if (value != null && !value.isBlank()) metadata.put(key, value); }
}
