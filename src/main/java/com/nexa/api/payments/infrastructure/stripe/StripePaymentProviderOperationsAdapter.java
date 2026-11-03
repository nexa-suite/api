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
    public boolean supportsBuyerPaymentMethodSetup() {
        return provider.supportsBuyerPaymentMethodSetup();
    }

    @Override
    public boolean supportsLocalSetupIntentConfirmation() {
        return provider.supportsLocalSetupIntentConfirmation();
    }

    @Override
    public ProviderCustomer createCustomer(CustomerRequest request) {
        StripePaymentProvider.ProviderCustomer customer = provider.createCustomer(
                new StripePaymentProvider.CustomerRequest(request.idempotencyKey(), request.metadata()));
        return customer == null ? null : new ProviderCustomer(customer.providerId(), customer.metadata());
    }

    @Override
    public java.util.Optional<ProviderCustomer> retrieveCustomer(String providerId) {
        return provider.retrieveCustomer(providerId).map(customer ->
                new ProviderCustomer(customer.providerId(), customer.metadata()));
    }

    @Override
    public ProviderSetupIntent createSetupIntent(SetupIntentRequest request) {
        StripePaymentProvider.ProviderSetupIntent intent = provider.createSetupIntent(
                new StripePaymentProvider.SetupIntentRequest(request.customerId(), request.idempotencyKey(),
                        request.metadata()));
        return intent == null ? null : setupIntent(intent);
    }

    @Override
    public java.util.Optional<ProviderSetupIntent> retrieveSetupIntent(String providerId) {
        return provider.retrieveSetupIntent(providerId).map(StripePaymentProviderOperationsAdapter::setupIntent);
    }

    @Override
    public java.util.Optional<ProviderPaymentMethod> retrievePaymentMethod(String providerId) {
        return provider.retrievePaymentMethod(providerId).map(method -> new ProviderPaymentMethod(
                method.providerId(), method.customerId(), method.type(), method.brand(), method.last4(),
                method.expiryMonth(), method.expiryYear()));
    }

    @Override
    public ProviderPaymentMethod detachPaymentMethod(String providerId) {
        StripePaymentProvider.ProviderPaymentMethod method = provider.detachPaymentMethod(providerId);
        return method == null ? null : new ProviderPaymentMethod(method.providerId(), method.customerId(),
                method.type(), method.brand(), method.last4(), method.expiryMonth(), method.expiryYear());
    }

    @Override
    public SignedProviderWebhook confirmLocalSetupIntent(String providerSetupIntentId) {
        StripePaymentProvider.SignedProviderWebhook webhook = provider.confirmLocalSetupIntent(providerSetupIntentId);
        return webhook == null ? null : new SignedProviderWebhook(webhook.payload(), webhook.signature());
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

    private static ProviderSetupIntent setupIntent(StripePaymentProvider.ProviderSetupIntent intent) {
        return new ProviderSetupIntent(intent.providerId(), intent.customerId(), intent.clientSecret(),
                intent.status(), intent.paymentMethodId(), intent.metadata());
    }
}
