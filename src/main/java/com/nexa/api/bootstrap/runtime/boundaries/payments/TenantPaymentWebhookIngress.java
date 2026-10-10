package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcPaymentProviderRouteRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.publicapi.TenantPaymentWebhookInboxIntake;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.infrastructure.persistence.PaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

/** Verifies callbacks, then stores only an opaque central route reference for Tenant payments. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
public final class TenantPaymentWebhookIngress {
    private static final String ROUTE_METADATA = "nexa_payment_route_id";
    private static final String WALLET_METADATA = "nexa_wallet_recharge_id";

    private final StripePaymentProvider stripe;
    private final JdbcPaymentProviderRouteRegistry routes;
    private final PaymentService centralLegacyPayments;
    private final TenantPaymentWebhookInboxIntake webhookInbox;

    public TenantPaymentWebhookIngress(StripePaymentProvider stripe,
            JdbcPaymentProviderRouteRegistry routes, PaymentService centralLegacyPayments,
            TenantPaymentWebhookInboxIntake webhookInbox) {
        this.stripe = Objects.requireNonNull(stripe);
        this.routes = Objects.requireNonNull(routes);
        this.centralLegacyPayments = Objects.requireNonNull(centralLegacyPayments);
        this.webhookInbox = Objects.requireNonNull(webhookInbox);
    }

    public PaymentModels.WebhookReceipt receive(String payload, String signature) {
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("Stripe webhook payload is required");
        StripePaymentProvider.StripeWebhookEvent event = stripe.verifyWebhook(payload, signature);
        requireIdentity(event);

        String walletRechargeId = event.metadata().get(WALLET_METADATA);
        if (walletRechargeId != null && !walletRechargeId.isBlank()) {
            // Wallet callbacks retain their separately accepted opaque wallet route and worker.
            return centralLegacyPayments.receiveStripeWebhook(payload, signature);
        }
        if (event.paymentIntentId() == null || !event.eventType().startsWith("payment_intent.")) {
            return new PaymentModels.WebhookReceipt(event.eventId(), "IGNORED");
        }

        UUID routeId = opaqueRouteId(event.metadata().get(ROUTE_METADATA));
        JdbcPaymentProviderRouteRegistry.Route route = routeId == null
                ? routes.findForVerifiedIntent(event.paymentIntentId()) : routes.findByRouteId(routeId);
        if (route == null) throw unavailable("No READY Tenant payment route matches this verified callback");
        if (!"STRIPE".equals(route.providerCode())
                || (route.providerPaymentIntentId() != null
                    && !route.providerPaymentIntentId().equals(event.paymentIntentId()))) {
            throw new IllegalArgumentException("Verified Stripe callback does not match its opaque payment route");
        }
        if (event.amountMinor() == null || event.amountMinor() != route.amountMinor()
                || event.currency() == null || !event.currency().matches("[A-Za-z]{3}")
                || !event.currency().equalsIgnoreCase(route.currency())) {
            throw new IllegalArgumentException("Verified Stripe callback amount or currency does not match its route");
        }
        if (route.providerPaymentIntentId() == null) {
            route = routes.bindProviderIntentFromVerifiedEvent(route, event.paymentIntentId());
        }
        if ("PREPARING".equals(route.status())) {
            routes.activateFromVerifiedEvent(route, event.paymentIntentId());
        }

        return webhookInbox.acceptVerifiedEvent(new TenantPaymentWebhookInboxIntake.VerifiedEvent(
                event.eventId(), event.eventType(), event.paymentIntentId(), event.paymentStatus(),
                event.amountMinor(), event.currency().toUpperCase(java.util.Locale.ROOT), sha256(signature),
                java.time.Instant.now(), route.routeId()));
    }

    private static UUID opaqueRouteId(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Verified Stripe payment route metadata is invalid");
        }
        return UUID.fromString(value);
    }

    private static void requireIdentity(StripePaymentProvider.StripeWebhookEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 160
                || event.eventType() == null || event.eventType().isBlank() || event.eventType().length() > 160
                || (event.paymentIntentId() != null && event.paymentIntentId().length() > 160)) {
            throw new IllegalArgumentException("Stripe webhook identity is invalid");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Stripe webhook signature digest could not be computed", exception);
        }
    }

    private static TenantBusinessDatabaseUnavailableException unavailable(String message) {
        return new TenantBusinessDatabaseUnavailableException(message);
    }
}
