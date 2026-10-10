package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcPaymentProviderRouteRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePaymentCallbackRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.payments.application.publicapi.TenantPaymentProviderEventProcessor;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentServiceFactory;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/** Cross-checks the central opaque provider route before one atomic Tenant payment callback phase. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
public final class TenantPaymentProviderEventProcessorAdapter implements TenantPaymentProviderEventProcessor {
    private final JdbcPaymentProviderRouteRegistry routes;
    private final TenantBusinessDatabasePaymentCallbackRouter callbackRouter;
    private final TenantPaymentServiceFactory services;

    public TenantPaymentProviderEventProcessorAdapter(JdbcPaymentProviderRouteRegistry routes,
            TenantBusinessDatabasePaymentCallbackRouter callbackRouter, TenantPaymentServiceFactory services) {
        this.routes = Objects.requireNonNull(routes);
        this.callbackRouter = Objects.requireNonNull(callbackRouter);
        this.services = Objects.requireNonNull(services);
    }

    @Override
    public Outcome process(VerifiedEvent event) {
        Objects.requireNonNull(event, "Verified Tenant payment event is required");
        if (event.eventId() == null || event.eventId().isBlank() || event.routeId() == null
                || event.providerPaymentIntentId() == null || event.amountMinor() == null
                || event.currency() == null || !event.currency().matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("Verified Tenant payment event is incomplete");
        }
        JdbcPaymentProviderRouteRegistry.Route route = routes.findByRouteId(event.routeId());
        if (route == null) throw unavailable("Opaque Tenant payment route is not READY");
        if (!"STRIPE".equals(route.providerCode())
                || !event.providerPaymentIntentId().equals(route.providerPaymentIntentId())
                || event.amountMinor() != route.amountMinor()
                || !event.currency().equalsIgnoreCase(route.currency())) {
            throw new IllegalArgumentException("Verified Stripe event does not match the central payment route");
        }
        if ("PREPARING".equals(route.status())) {
            routes.activateFromVerifiedEvent(route, event.providerPaymentIntentId());
            route = routes.findByRouteId(event.routeId());
            if (route == null) throw unavailable("Tenant payment route changed before callback dispatch");
        }

        JdbcPaymentProviderRouteRegistry.Route routed = route;
        TenantPaymentSession.TenantPaymentProviderEventResult result = callbackRouter.inTransaction(routed,
                (jdbc, transactionManager) -> services.bindTo(jdbc, transactionManager)
                        .applyTenantPaymentProviderEvent(routed.tenantId().value(), routed.workspaceId().value(),
                                routed.paymentId(), event.eventId(), event.eventType(),
                                event.providerPaymentIntentId(), event.providerStatus(), event.amountMinor(),
                                event.currency().toUpperCase(Locale.ROOT)));

        if ("SUCCEEDED".equals(result.paymentStatus())) routes.markSucceeded(routed);
        else if ("FAILED".equals(result.paymentStatus())) routes.markFailed(routed);
        else if ("CANCELLED".equals(result.paymentStatus())) routes.markCancelled(routed);
        return result.outcome() == TenantPaymentSession.TenantPaymentProviderEventOutcome.PROCESSED
                ? Outcome.PROCESSED : Outcome.IGNORED;
    }

    private static TenantBusinessDatabaseUnavailableException unavailable(String message) {
        return new TenantBusinessDatabaseUnavailableException(message);
    }
}
