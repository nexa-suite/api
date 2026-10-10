package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcPaymentProviderRouteRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.port.PaymentPersistencePort;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.application.publicapi.PaymentWebhookInboxWorker;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentServiceFactory;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentSession;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Tenant-routed Payments persistence; it never falls through to the central Payments store. */
@Component
@Primary
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
public final class TenantPaymentPersistenceAdapter implements PaymentPersistencePort {
    private final TenantBusinessDatabaseRouter router;
    private final TenantBusinessDatabaseAuthority authority;
    private final TenantPaymentServiceFactory services;
    private final JdbcPaymentProviderRouteRegistry providerRoutes;
    private final StripePaymentProvider stripe;
    private final TenantPaymentWebhookIngress webhookIngress;
    private final PaymentWebhookInboxWorker inboxWorker;

    public TenantPaymentPersistenceAdapter(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabaseAuthority authority, TenantPaymentServiceFactory services,
            JdbcPaymentProviderRouteRegistry providerRoutes, StripePaymentProvider stripe,
            TenantPaymentWebhookIngress webhookIngress, PaymentWebhookInboxWorker inboxWorker) {
        this.router = Objects.requireNonNull(router);
        this.authority = Objects.requireNonNull(authority);
        this.services = Objects.requireNonNull(services);
        this.providerRoutes = Objects.requireNonNull(providerRoutes);
        this.stripe = Objects.requireNonNull(stripe);
        this.webhookIngress = Objects.requireNonNull(webhookIngress);
        this.inboxWorker = Objects.requireNonNull(inboxWorker);
    }

    @Override public PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(
            CurrentAccessContext context, int page, int size) {
        return read(context, service -> service.listReceivables(context, page, size));
    }

    @Override public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPayments(
            CurrentAccessContext context, int page, int size, String method, String status) {
        return read(context, service -> service.listPayments(context, page, size, method, status));
    }

    @Override public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPaymentsForReceivable(
            CurrentAccessContext context, UUID receivableId, int page, int size) {
        return read(context, service -> service.listPaymentsForReceivable(context, receivableId, page, size));
    }

    @Override public PaymentModels.Page<PaymentModels.ReconciliationCaseView> listReconciliationCases(
            CurrentAccessContext context, int page, int size, String state) {
        return read(context, service -> service.listReconciliationCases(context, page, size, state));
    }

    @Override public PaymentModels.ReconciliationCaseView retryReconciliationCase(CurrentAccessContext context,
            UUID caseId, String operatorNote, String idempotencyKey) {
        return write(context, service -> service.retryReconciliationCase(context, caseId, operatorNote, idempotencyKey));
    }

    @Override public PaymentModels.ReceivableView getReceivable(CurrentAccessContext context, UUID receivableId) {
        return read(context, service -> service.getReceivable(context, receivableId));
    }

    @Override public PaymentModels.PaymentView getPayment(CurrentAccessContext context, UUID paymentId) {
        return read(context, service -> service.getPayment(context, paymentId));
    }

    @Override public PaymentModels.ReceivableView createReceivable(CurrentAccessContext context,
            ReceivableCommand request) {
        return write(context, service -> service.createReceivable(context, request));
    }

    @Override public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context,
            UUID receivableId, String idempotencyKey) {
        requireCurrentScope(context);
        if (!stripe.supportsPaymentIntentCreation()) {
            throw unavailable("Tenant card payments require the configured PaymentIntent provider");
        }
        try {
            TenantPaymentSession.TenantCardPaymentPreparation preparation = router.inTenantSession(context, session ->
                    services.bindTo(session.jdbcTemplate(), session.transactionManager())
                            .prepareTenantCardPayment(context, receivableId, idempotencyKey));

            TenantBusinessDatabaseBinding binding = verifiedBinding(context);
            JdbcPaymentProviderRouteRegistry.Route route = providerRoutes.registerPreparing(binding,
                    context.workspaceId(), preparation.paymentId(), preparation.amountMinor(), preparation.currency());

            if (preparation.providerPaymentIntentId() != null) {
                if (!preparation.providerPaymentIntentId().equals(route.providerPaymentIntentId())) {
                    throw unavailable("Tenant card payment route does not match its stored provider intent");
                }
                return Objects.requireNonNull(preparation.replay(),
                        "Stored Stripe intent replay must include its client result");
            }

            StripePaymentProvider.PaymentIntent intent;
            try {
                intent = stripe.createPaymentIntent(new StripePaymentProvider.PaymentIntentRequest(
                        preparation.amountMinor(), preparation.currency(), preparation.providerIdempotencyKey(),
                        Map.of("nexa_payment_route_id", route.routeId().toString())));
                requireProviderIntent(intent);
            } catch (RuntimeException failure) {
                try {
                    router.inTenantSession(context, session -> {
                        services.bindTo(session.jdbcTemplate(), session.transactionManager())
                                .recordTenantCardPaymentFailure(context, preparation, failure);
                        return null;
                    });
                } catch (RuntimeException recordFailure) {
                    failure.addSuppressed(recordFailure);
                }
                throw failure;
            }

            providerRoutes.bindProviderIntent(route, intent.providerId());
            PaymentModels.PaymentIntentView result = router.inTenantSession(context, session ->
                    services.bindTo(session.jdbcTemplate(), session.transactionManager())
                            .persistTenantCardPayment(context, preparation, intent));
            providerRoutes.activate(route, intent.providerId());
            return result;
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override public PaymentModels.PaymentView confirmTestCardPayment(CurrentAccessContext context,
            UUID receivableId, String clientSecret) {
        requireCurrentScope(context);
        try {
            TenantPaymentSession.TenantTestCardPreparation preparation = router.inTenantSession(context, session ->
                    services.bindTo(session.jdbcTemplate(), session.transactionManager())
                            .prepareTenantTestCardConfirmation(context, receivableId));
            if ("SUCCEEDED".equals(preparation.status())) {
                return read(context, service -> service.getPayment(context, preparation.paymentId()));
            }
            JdbcPaymentProviderRouteRegistry.Route route = providerRoutes.findForVerifiedIntent(
                    preparation.providerPaymentIntentId());
            if (route == null || !route.paymentId().equals(preparation.paymentId())
                    || !route.tenantId().equals(context.tenantId())
                    || !route.workspaceId().equals(context.workspaceId())) {
                throw unavailable("Tenant payment callback route is unavailable");
            }
            TenantPaymentSession.TenantTestCardWebhook event = router.inTenantSession(context, session ->
                    services.bindTo(session.jdbcTemplate(), session.transactionManager())
                            .confirmTenantTestCardPayment(context, receivableId, clientSecret, route.routeId()));
            PaymentModels.WebhookReceipt receipt = webhookIngress.receive(event.payload(), event.signature());
            inboxWorker.processEvent(receipt.eventId());
            return read(context, service -> service.getPayment(context, preparation.paymentId()));
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override public PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context,
            UUID receivableId, String idempotencyKey) {
        return write(context, service -> service.createCreditLinePayment(context, receivableId, idempotencyKey));
    }

    @Override public PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId,
            String idempotencyKey, String transferReference, UUID proofEvidenceId) {
        return write(context, service -> service.createBankTransfer(context, receivableId, idempotencyKey,
                transferReference, proofEvidenceId));
    }

    @Override public PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext context, UUID paymentId,
            String action, String reason, String idempotencyKey) {
        return write(context, service -> service.reviewBankTransfer(context, paymentId, action, reason, idempotencyKey));
    }

    @Override public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) {
        return webhookIngress.receive(payload, signature);
    }

    @Override public void processStripeWebhookInbox() {
        inboxWorker.processPending();
    }

    private <T> T read(CurrentAccessContext context, Function<TenantPaymentSession, T> operation) {
        try {
            return router.inTenantSession(context, session -> {
                TenantPaymentSession service = services.bindTo(session.jdbcTemplate(), session.transactionManager());
                return session.inTransaction(ignored -> operation.apply(service));
            });
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private <T> T write(CurrentAccessContext context, Function<TenantPaymentSession, T> operation) {
        try {
            return router.inTenantSession(context, session ->
                    operation.apply(services.bindTo(session.jdbcTemplate(), session.transactionManager())));
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                | DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private TenantBusinessDatabaseBinding verifiedBinding(CurrentAccessContext context) {
        TenantBusinessDatabaseBinding binding = authority.requireReadyBinding(context);
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        requirements.verifyRequiredSqlAssets();
        if (binding.verifiedSchemaManifestSha256() == null
                || !requirements.schemaManifestDigest().equals(binding.verifiedSchemaManifestSha256())) {
            throw new TenantBusinessDatabaseSchemaManifestMismatchException();
        }
        return binding;
    }

    private static void requireCurrentScope(CurrentAccessContext context) {
        if (context == null) throw new AccessPolicyViolation("Verified Tenant payment scope is required");
        RlsRequestScope.Scope scope = RlsRequestScope.current();
        if (scope == null || !context.tenantId().value().equals(scope.tenantId())
                || !context.workspaceId().value().equals(scope.workspaceId())) {
            throw new AccessPolicyViolation("Payment operation requires matching verified Tenant and Workspace scope");
        }
    }

    private static void requireProviderIntent(StripePaymentProvider.PaymentIntent intent) {
        if (intent == null || intent.providerId() == null || intent.providerId().isBlank()
                || intent.providerId().length() > 255 || intent.status() == null || intent.status().isBlank()
                || intent.clientSecret() == null || intent.clientSecret().isBlank()) {
            throw new IllegalStateException("Stripe returned an incomplete Tenant payment intent");
        }
    }

    private static TechnicalFailureException unavailable(String message) {
        return new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                message);
    }

    private static TechnicalFailureException unavailable(RuntimeException cause) {
        return new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                "Tenant payment persistence is unavailable", cause);
    }
}
