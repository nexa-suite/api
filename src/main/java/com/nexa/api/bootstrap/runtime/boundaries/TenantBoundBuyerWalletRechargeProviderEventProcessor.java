package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcWalletRechargeProviderRouteRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWalletRechargeCallbackRouter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargeProviderEventProcessor;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargeTenantCommands;
import com.nexa.api.payments.tenantdatabase.BuyerWalletTenantDatabaseAdapterFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Verifies central route, then commits the provider event and ledger in one dedicated Tenant transaction. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.buyer-wallet-recharge", name = "enabled", havingValue = "true")
public final class TenantBoundBuyerWalletRechargeProviderEventProcessor
        implements BuyerWalletRechargeProviderEventProcessor {
    private final JdbcWalletRechargeProviderRouteRegistry routes;
    private final TenantBusinessDatabaseWalletRechargeCallbackRouter callbackRouter;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final BuyerWalletTenantDatabaseAdapterFactory walletAdapters;

    public TenantBoundBuyerWalletRechargeProviderEventProcessor(JdbcWalletRechargeProviderRouteRegistry routes,
            TenantBusinessDatabaseWalletRechargeCallbackRouter callbackRouter,
            TenantCustomerAccountQueryFactory customerAccounts,
            BuyerWalletTenantDatabaseAdapterFactory walletAdapters) {
        this.routes = Objects.requireNonNull(routes);
        this.callbackRouter = Objects.requireNonNull(callbackRouter);
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.walletAdapters = Objects.requireNonNull(walletAdapters);
    }

    @Override
    public Outcome process(VerifiedEvent event) {
        Objects.requireNonNull(event, "Verified Stripe event is required");
        if (event.tenantId() == null || event.workspaceId() == null || event.rechargeId() == null) {
            return Outcome.IGNORED;
        }
        JdbcWalletRechargeProviderRouteRegistry.Route route = routes.findForVerifiedEvent(
                event.tenantId(), event.workspaceId(), event.rechargeId());
        if (route == null) return Outcome.IGNORED;
        if ("PREPARING".equals(route.status()) && route.providerPaymentIntentId() == null) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant wallet recharge provider intent registration is still pending");
        }
        if (!"AWAITING_PAYMENT".equals(route.status())) return Outcome.IGNORED;
        if (route.providerPaymentIntentId() == null) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant wallet recharge provider intent registration is incomplete");
        }

        TenantBusinessDatabaseBinding workerBinding = new TenantBusinessDatabaseBinding(route.tenantId(),
                route.databaseIdentity(), route.callbackCredentialSecretReference(),
                route.verifiedSchemaManifestSha256());
        Outcome outcome;
        try {
            outcome = callbackRouter.inTransaction(workerBinding, route.workspaceId(), tenantJdbc -> {
                BuyerWalletRechargeTenantCommands commands = walletAdapters.bindRechargeCommandsTo(
                        tenantJdbc, customerAccounts.bindTo(tenantJdbc));
                return commands.processVerifiedProviderEvent(route.tenantId().value(), route.workspaceId().value(), event);
            });
        } catch (DataAccessException failure) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant wallet recharge callback transaction is unavailable", failure);
        }
        if (outcome == Outcome.PROCESSED) routes.markSucceeded(route);
        else if (outcome == Outcome.CANCELLED) routes.markCancelled(route);
        return outcome;
    }
}
