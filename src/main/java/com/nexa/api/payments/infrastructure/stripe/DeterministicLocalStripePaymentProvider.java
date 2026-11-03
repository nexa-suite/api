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
    private final Map<String, ProviderCustomer> customers = new ConcurrentHashMap<>();
    private final Map<String, ProviderSetupIntent> setupIntents = new ConcurrentHashMap<>();
    private final Map<String, ProviderPaymentMethod> paymentMethods = new ConcurrentHashMap<>();

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
    public boolean supportsBuyerPaymentMethodSetup() { return true; }

    @Override
    public boolean supportsLocalSetupIntentConfirmation() { return true; }

    @Override
    public ProviderCustomer createCustomer(CustomerRequest request) {
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Local Buyer Customer idempotency key is required");
        }
        String id = "cus_local_" + stableId(request.idempotencyKey());
        ProviderCustomer proposed = new ProviderCustomer(id, request.metadata());
        ProviderCustomer existing = customers.putIfAbsent(id, proposed);
        if (existing != null && !existing.equals(proposed)) {
            throw new IllegalArgumentException("Local Buyer Customer idempotency key was reused with different metadata");
        }
        return existing == null ? proposed : existing;
    }

    @Override
    public Optional<ProviderCustomer> retrieveCustomer(String providerId) {
        return Optional.ofNullable(customers.get(providerId));
    }

    @Override
    public ProviderSetupIntent createSetupIntent(SetupIntentRequest request) {
        if (request == null || request.customerId() == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || !customers.containsKey(request.customerId())) {
            throw new IllegalArgumentException("Local Buyer SetupIntent requires a known Customer and idempotency key");
        }
        String id = "seti_local_" + stableId(request.idempotencyKey());
        ProviderSetupIntent proposed = new ProviderSetupIntent(id, request.customerId(), id + "_secret_local",
                "requires_payment_method", null, request.metadata());
        ProviderSetupIntent existing = setupIntents.putIfAbsent(id, proposed);
        if (existing != null && (!request.customerId().equals(existing.customerId())
                || !request.metadata().equals(existing.metadata()))) {
            throw new IllegalArgumentException("Local SetupIntent idempotency key was reused with different scope");
        }
        return existing == null ? proposed : existing;
    }

    @Override
    public Optional<ProviderSetupIntent> retrieveSetupIntent(String providerId) {
        return Optional.ofNullable(setupIntents.get(providerId));
    }

    @Override
    public Optional<ProviderPaymentMethod> retrievePaymentMethod(String providerId) {
        return Optional.ofNullable(paymentMethods.get(providerId));
    }

    @Override
    public ProviderPaymentMethod detachPaymentMethod(String providerId) {
        return paymentMethods.remove(providerId);
    }

    /** Emits a synthetic local-only card result and an HMAC-signed callback; accepts no card data. */
    @Override
    public SignedProviderWebhook confirmLocalSetupIntent(String providerSetupIntentId) {
        if (providerSetupIntentId == null || providerSetupIntentId.isBlank()) {
            throw new IllegalArgumentException("Local SetupIntent id is required");
        }
        ProviderSetupIntent current = setupIntents.get(providerSetupIntentId);
        if (current == null) throw new IllegalArgumentException("Local SetupIntent was not found");
        if ("succeeded".equals(current.status())) {
            return signedSetupWebhook(current);
        }
        String methodId = "pm_local_" + stableId(providerSetupIntentId);
        ProviderPaymentMethod method = new ProviderPaymentMethod(methodId, current.customerId(), "card", "visa",
                "4242", 12L, 2035L);
        ProviderPaymentMethod existingMethod = paymentMethods.putIfAbsent(methodId, method);
        if (existingMethod != null && !existingMethod.equals(method)) {
            throw new IllegalStateException("Local SetupIntent PaymentMethod identity conflicts");
        }
        ProviderSetupIntent succeeded = new ProviderSetupIntent(current.providerId(), current.customerId(),
                current.clientSecret(), "succeeded", methodId, current.metadata());
        setupIntents.replace(providerSetupIntentId, current, succeeded);
        ProviderSetupIntent saved = setupIntents.get(providerSetupIntentId);
        if (!"succeeded".equals(saved.status()) || !methodId.equals(saved.paymentMethodId())) {
            throw new IllegalStateException("Local SetupIntent confirmation did not reach a stable result");
        }
        return signedSetupWebhook(saved);
    }

    @Override
    public Refund refundPayment(String providerId, long amountMinor, String currency, String idempotencyKey) {
        if (providerId == null || !intents.containsKey(providerId)) throw new IllegalArgumentException("Local PaymentIntent was not found");
        return new Refund("re_local_" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""), "succeeded");
    }

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
        putIfPresent(metadata, "nexa_wallet_recharge_id", first(payload, "nexa_wallet_recharge_id", null));
        putIfPresent(metadata, "nexa_payment_route_id", first(payload, "nexa_payment_route_id", null));
        putIfPresent(metadata, "nexa_buyer_membership_id", first(payload, "nexa_buyer_membership_id", null));
        putIfPresent(metadata, "nexa_client_account_id", first(payload, "nexa_client_account_id", null));
        putIfPresent(metadata, "nexa_setup_attempt_id", first(payload, "nexa_setup_attempt_id", null));
        String eventType = first(payload, "type", null);
        boolean setupEvent = eventType != null && eventType.startsWith("setup_intent.");
        return new StripeWebhookEvent(first(payload, "id", null), eventType,
                setupEvent ? null : first(payload, "payment_intent_id", first(payload, "object_id", null)),
                first(payload, "status", null), number(payload, "amount"), optionalUpper(first(payload, "currency", null)),
                metadata, setupEvent ? first(payload, "setup_intent_id", null) : null,
                setupEvent ? first(payload, "customer_id", null) : null,
                setupEvent ? first(payload, "payment_method_id", null) : null);
    }

    private SignedProviderWebhook signedSetupWebhook(ProviderSetupIntent intent) {
        String eventId = "evt_local_setup_" + stableId(intent.providerId());
        StringBuilder payload = new StringBuilder("{\"id\":\"").append(eventId)
                .append("\",\"type\":\"setup_intent.succeeded\",\"setup_intent_id\":\"")
                .append(intent.providerId()).append("\",\"customer_id\":\"").append(intent.customerId())
                .append("\",\"payment_method_id\":\"").append(intent.paymentMethodId())
                .append("\",\"status\":\"succeeded\"");
        intent.metadata().forEach((key, value) -> payload.append(",\"").append(key).append("\":\"")
                .append(value).append("\""));
        payload.append('}');
        String serialized = payload.toString();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        return new SignedProviderWebhook(serialized, "t=" + timestamp + ",v1=" + hmac(timestamp + "." + serialized));
    }

    private static String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
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
