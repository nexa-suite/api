package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.application.publicapi.provider.PaymentProviderOperations;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Keeps the provider SPI and its general metadata map inside BC-08. */
@Component
@Profile("!test")
public final class StripePaymentProviderOperationsAdapter implements PaymentProviderOperations {
    private final StripePaymentProvider provider;

    public StripePaymentProviderOperationsAdapter(StripePaymentProvider provider) {
        this.provider = Objects.requireNonNull(provider);
    }

    @Override
    public boolean supportsPaymentIntentCreation() {
        return provider.supportsPaymentIntentCreation();
    }

    @Override
    public PaymentIntent createPaymentIntent(PaymentIntentRequest request) {
        Objects.requireNonNull(request, "PaymentIntent request is required");
        Map<String, String> metadata = new LinkedHashMap<>();
        put(metadata, "nexa_payment_route_id", request.paymentRouteId());
        put(metadata, "nexa_wallet_recharge_id", request.walletRechargeId());
        put(metadata, "nexa_tenant_id", request.tenantId());
        put(metadata, "nexa_workspace_id", request.workspaceId());
        StripePaymentProvider.PaymentIntent intent = provider.createPaymentIntent(
                new StripePaymentProvider.PaymentIntentRequest(request.amountMinor(), request.currency(),
                        request.idempotencyKey(), Map.copyOf(metadata)));
        return intent == null ? null : new PaymentIntent(intent.providerId(), intent.clientSecret(), intent.status());
    }

    @Override
    public Refund refundPayment(String providerId, long amountMinor, String currency, String idempotencyKey) {
        StripePaymentProvider.Refund refund = provider.refundPayment(providerId, amountMinor, currency, idempotencyKey);
        return refund == null ? null : new Refund(refund.providerRefundId(), refund.status());
    }

    private static void put(Map<String, String> metadata, String name, Object value) {
        if (value != null) metadata.put(name, value.toString());
    }
}
