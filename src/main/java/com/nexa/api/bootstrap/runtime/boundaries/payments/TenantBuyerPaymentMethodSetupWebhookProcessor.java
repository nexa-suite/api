package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePaymentCallbackRouter;
import com.nexa.api.bootstrap.runtime.boundaries.TenantPaymentServiceBindingsFactory;
import com.nexa.api.payments.application.publicapi.TenantPaymentSession;
import com.nexa.api.payments.application.publicapi.model.PaymentModels;
import com.nexa.api.payments.application.publicapi.provider.PaymentProviderOperations;
import com.nexa.api.payments.application.publicapi.webhooks.PaymentWebhookSignatureVerifier;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Persists a Buyer card only after a signed success event and server-side Stripe object retrieval. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
public final class TenantBuyerPaymentMethodSetupWebhookProcessor {
    private static final String TENANT = "nexa_tenant_id";
    private static final String WORKSPACE = "nexa_workspace_id";
    private static final String MEMBERSHIP = "nexa_buyer_membership_id";
    private static final String CLIENT_ACCOUNT = "nexa_client_account_id";
    private static final String ATTEMPT = "nexa_setup_attempt_id";

    private final PaymentProviderOperations provider;
    private final TenantBusinessDatabasePaymentCallbackRouter callbackRouter;
    private final TenantPaymentServiceBindingsFactory services;

    public TenantBuyerPaymentMethodSetupWebhookProcessor(PaymentProviderOperations provider,
            TenantBusinessDatabasePaymentCallbackRouter callbackRouter,
            TenantPaymentServiceBindingsFactory services) {
        this.provider = Objects.requireNonNull(provider);
        this.callbackRouter = Objects.requireNonNull(callbackRouter);
        this.services = Objects.requireNonNull(services);
    }

    public PaymentModels.WebhookReceipt process(PaymentWebhookSignatureVerifier.VerifiedEvent event,
            String signature) {
        Objects.requireNonNull(event, "Verified SetupIntent callback is required");
        if (!isSupportedEvent(event.eventType())) {
            return new PaymentModels.WebhookReceipt(event.eventId(), "IGNORED");
        }
        if (event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 160
                || event.setupIntentId() == null || event.setupIntentId().isBlank()
                || event.setupIntentId().length() > 255 || event.providerCustomerId() == null
                || event.providerCustomerId().isBlank() || event.providerCustomerId().length() > 255) {
            throw new IllegalArgumentException("Verified Buyer SetupIntent callback identity is incomplete");
        }

        UUID tenantId = metadataUuid(event.metadata(), TENANT);
        UUID workspaceId = metadataUuid(event.metadata(), WORKSPACE);
        UUID membershipId = metadataUuid(event.metadata(), MEMBERSHIP);
        UUID clientAccountId = metadataUuid(event.metadata(), CLIENT_ACCOUNT);
        UUID attemptId = metadataUuid(event.metadata(), ATTEMPT);
        PaymentProviderOperations.ProviderSetupIntent setupIntent = provider.retrieveSetupIntent(event.setupIntentId())
                .orElseThrow(() -> unavailable("Signed Stripe SetupIntent cannot be retrieved"));
        if (!event.setupIntentId().equals(setupIntent.providerId())
                || !event.providerCustomerId().equals(setupIntent.customerId())
                || !event.metadata().equals(setupIntent.metadata())
                || !event.eventType().equals("setup_intent." + providerEventSuffix(setupIntent.status()))) {
            throw new IllegalArgumentException("Signed Stripe SetupIntent does not match the current provider object");
        }

        Map<String, String> expectedCustomerMetadata = customerMetadata(event.metadata());
        PaymentProviderOperations.ProviderCustomer customer = provider.retrieveCustomer(event.providerCustomerId())
                .orElseThrow(() -> unavailable("Buyer Stripe Customer cannot be retrieved"));
        if (!event.providerCustomerId().equals(customer.providerId())
                || !expectedCustomerMetadata.equals(customer.metadata())) {
            throw new IllegalArgumentException("Stripe Customer is not linked to this Buyer scope");
        }

        PaymentProviderOperations.ProviderPaymentMethod method = null;
        if ("setup_intent.succeeded".equals(event.eventType())) {
            String methodId = setupIntent.paymentMethodId();
            if (methodId == null || methodId.isBlank() || methodId.length() > 255
                    || event.providerPaymentMethodId() == null
                    || !methodId.equals(event.providerPaymentMethodId())) {
                throw new IllegalArgumentException("Succeeded Stripe SetupIntent has no matching PaymentMethod");
            }
            method = provider.retrievePaymentMethod(methodId)
                    .orElseThrow(() -> unavailable("SetupIntent PaymentMethod cannot be retrieved"));
            if (!methodId.equals(method.providerId()) || !event.providerCustomerId().equals(method.customerId())
                    || !"card".equalsIgnoreCase(method.type()) || method.brand() == null || method.brand().isBlank()
                    || method.brand().length() > 40 || method.last4() == null || !method.last4().matches("[0-9]{4}")
                    || method.expiryMonth() == null || method.expiryMonth() < 1 || method.expiryMonth() > 12
                    || method.expiryYear() == null || method.expiryYear() < 2000 || method.expiryYear() > 9999) {
                throw new IllegalArgumentException("Retrieved Stripe PaymentMethod is not a valid saved card");
            }
        }

        String signatureDigest = sha256(signature);
        PaymentProviderOperations.ProviderPaymentMethod verifiedMethod = method;
        return callbackRouter.inBuyerPaymentMethodSetupTransaction(new TenantId(tenantId),
                new WorkspaceId(workspaceId), membershipId, (jdbc, transactionManager) ->
                        services.bindTo(jdbc, transactionManager).applyBuyerPaymentMethodSetupWebhook(tenantId,
                                workspaceId, membershipId, clientAccountId, attemptId, setupIntent.providerId(),
                                customer.providerId(), event.eventId(), event.eventType(), setupIntent.status(),
                                signatureDigest, verifiedMethod));
    }

    private static boolean isSupportedEvent(String eventType) {
        return "setup_intent.succeeded".equals(eventType) || "setup_intent.setup_failed".equals(eventType)
                || "setup_intent.canceled".equals(eventType);
    }

    private static String providerEventSuffix(String status) {
        return switch (status == null ? "" : status.toLowerCase(java.util.Locale.ROOT)) {
            case "succeeded" -> "succeeded";
            case "canceled" -> "canceled";
            case "requires_payment_method" -> "setup_failed";
            default -> throw new IllegalArgumentException("Stripe SetupIntent is not in a callback terminal state");
        };
    }

    private static UUID metadataUuid(Map<String, String> metadata, String key) {
        String value = metadata == null ? null : metadata.get(key);
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Signed Stripe Buyer setup metadata is incomplete", invalid);
        }
    }

    private static Map<String, String> customerMetadata(Map<String, String> metadata) {
        LinkedHashMap<String, String> expected = new LinkedHashMap<>(metadata);
        expected.remove(ATTEMPT);
        return Map.copyOf(expected);
    }

    private static String sha256(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Stripe signature is required");
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Stripe signature digest could not be computed", exception);
        }
    }

    private static TenantBusinessDatabaseUnavailableException unavailable(String message) {
        return new TenantBusinessDatabaseUnavailableException(message);
    }
}
