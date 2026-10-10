package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery.Scope;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentServiceFactory;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentSession;
import com.nexa.api.bootstrap.runtime.events.VerifiedSystemWorkflowAccessContextResolver;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Tenant-scoped reconciliation runner; every database phase gets a fresh verified route. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.payments.enabled", havingValue = "true")
public final class TenantPaymentReconciliationWorkerScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TenantPaymentReconciliationWorkerScheduler.class);
    private static final int SCOPE_PAGE_SIZE = 50;
    private static final int CASES_PER_WORKSPACE = 10;

    private final TenantBusinessDocumentWorkerScopeQuery scopes;
    private final TenantBusinessDatabaseRouter router;
    private final TenantPaymentServiceFactory paymentServices;
    private final VerifiedSystemWorkflowAccessContextResolver workflowActors;
    private final StripePaymentProvider stripe;
    private final AtomicReference<Scope> after = new AtomicReference<>();

    public TenantPaymentReconciliationWorkerScheduler(TenantBusinessDocumentWorkerScopeQuery scopes,
            TenantBusinessDatabaseRouter router, TenantPaymentServiceFactory paymentServices,
            VerifiedSystemWorkflowAccessContextResolver workflowActors, StripePaymentProvider stripe) {
        this.scopes = Objects.requireNonNull(scopes, "Central READY Tenant scope query is required");
        this.router = Objects.requireNonNull(router, "Tenant database router is required");
        this.paymentServices = Objects.requireNonNull(paymentServices, "Tenant Payment bindings are required");
        this.workflowActors = Objects.requireNonNull(workflowActors, "Persisted SYSTEM_WORKFLOW resolver is required");
        this.stripe = Objects.requireNonNull(stripe, "Payment provider is required");
    }

    @Scheduled(fixedDelayString = "${nexa.payments.reconciliation-worker-delay-ms:5000}")
    public synchronized void processNextWorkspacePage() {
        Scope cursor = after.get();
        List<Scope> page = List.copyOf(scopes.listReadyWorkspaces(
                cursor == null ? null : cursor.tenantId(), cursor == null ? null : cursor.workspaceId(),
                SCOPE_PAGE_SIZE));
        if (page.isEmpty()) {
            after.set(null);
            return;
        }
        if (page.size() > SCOPE_PAGE_SIZE) {
            throw new IllegalStateException("Tenant payment scope query exceeded its bounded page size");
        }
        for (Scope scope : page) process(scope);
        after.set(page.size() < SCOPE_PAGE_SIZE ? null : page.getLast());
    }

    private void process(Scope scope) {
        RlsRequestScope.Scope previousScope = RlsRequestScope.current();
        boolean previousCrossScope = RlsRequestScope.crossScopeWorkspaceScanEnabled();
        try {
            RlsRequestScope.clearCrossScopeWorkspaceScan();
            RlsRequestScope.set(scope.tenantId(), scope.workspaceId());
            for (int index = 0; index < CASES_PER_WORKSPACE; index++) {
                if (!scopes.isReadyWorkspace(scope.tenantId(), scope.workspaceId())) return;
                CurrentAccessContext claimActor = workflowActors.resolve(scope.tenantId(), scope.workspaceId());
                TenantPaymentSession.TenantReconciliationRefundPreparation preparation = router.inTenantSession(
                        claimActor, session -> session.inTransaction(ignored -> paymentServices.bindTo(
                                session.jdbcTemplate(), session.transactionManager())
                                .claimNextTenantReconciliationRefund(claimActor)));
                if (preparation == null) return;

                if (!scopes.isReadyWorkspace(scope.tenantId(), scope.workspaceId())) return;
                workflowActors.resolve(scope.tenantId(), scope.workspaceId());
                StripePaymentProvider.Refund refund = null;
                RuntimeException providerFailure = null;
                try {
                    if (preparation.providerId() == null || preparation.providerId().isBlank()) {
                        throw new IllegalStateException("Captured payment has no provider reference");
                    }
                    refund = stripe.refundPayment(preparation.providerId(), preparation.amountMinor(),
                            preparation.currency(), preparation.providerIdempotencyKey());
                } catch (RuntimeException exception) {
                    providerFailure = exception;
                }

                if (!scopes.isReadyWorkspace(scope.tenantId(), scope.workspaceId())) return;
                CurrentAccessContext resultActor = workflowActors.resolve(scope.tenantId(), scope.workspaceId());
                StripePaymentProvider.Refund finalRefund = refund;
                RuntimeException finalProviderFailure = providerFailure;
                router.inTenantSession(resultActor, session -> paymentServices.bindTo(session.jdbcTemplate(),
                        session.transactionManager()).recordTenantReconciliationRefund(resultActor, preparation,
                                finalRefund, finalProviderFailure));
                if (!scopes.isReadyWorkspace(scope.tenantId(), scope.workspaceId())) return;
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Tenant payment reconciliation scope failed; it will retry on a later scan tenantId={} workspaceId={}",
                    scope.tenantId(), scope.workspaceId());
        } finally {
            RlsRequestScope.clear();
            if (previousScope != null) RlsRequestScope.set(previousScope.tenantId(), previousScope.workspaceId());
            if (previousCrossScope) RlsRequestScope.enableCrossScopeWorkspaceScan();
        }
    }
}
