package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.JdbcWalletRechargeProviderRouteRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargePort;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargeTenantCommands;
import com.nexa.api.payments.application.publicapi.BuyerWalletStoreUnavailableException;
import com.nexa.api.payments.tenantdatabase.BuyerWalletTenantDatabaseAdapterFactory;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Creates and reads recharge intents through one verified Tenant. External PSP call sits between local transactions. */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.buyer-wallet-recharge", name = "enabled", havingValue = "true")
public final class TenantBoundBuyerWalletRechargePort implements BuyerWalletRechargePort {
    private final TenantBusinessDatabaseRouter router;
    private final TenantBusinessDatabaseAuthority authority;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final BuyerWalletTenantDatabaseAdapterFactory walletAdapters;
    private final JdbcWalletRechargeProviderRouteRegistry providerRoutes;
    private final StripePaymentProvider stripe;

    public TenantBoundBuyerWalletRechargePort(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabaseAuthority authority, TenantCustomerAccountQueryFactory customerAccounts,
            BuyerWalletTenantDatabaseAdapterFactory walletAdapters,
            JdbcWalletRechargeProviderRouteRegistry providerRoutes, StripePaymentProvider stripe) {
        this.router = Objects.requireNonNull(router);
        this.authority = Objects.requireNonNull(authority);
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.walletAdapters = Objects.requireNonNull(walletAdapters);
        this.providerRoutes = Objects.requireNonNull(providerRoutes);
        this.stripe = Objects.requireNonNull(stripe);
    }

    @Override
    public BuyerWalletModels.RechargeIntentView create(CurrentAccessContext context, BigDecimal amountPEN,
            String idempotencyKey) {
        requireBuyer(context, PermissionKey.PAYMENT_CREATE);
        requireCentralScope(context);
        if (!stripe.supportsPaymentIntentCreation()) throw new BuyerWalletStoreUnavailableException();

        try {
            TenantBusinessDatabaseBinding binding = authority.requireReadyBinding(context);
            TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
            requirements.verifyRequiredSqlAssets();
            if (binding.verifiedSchemaManifestSha256() == null
                    || !requirements.schemaManifestDigest().equals(binding.verifiedSchemaManifestSha256())) {
                throw new TenantBusinessDatabaseSchemaManifestMismatchException();
            }
            providerRoutes.requireCallbackCredential(binding, context.workspaceId());
            BuyerWalletRechargeTenantCommands.Claim claim = router.inTransaction(context, tenantJdbc -> {
                CustomerAccountQuery tenantAccounts = customerAccounts.bindTo(tenantJdbc);
                BuyerWalletRechargeTenantCommands commands = walletAdapters.bindRechargeCommandsTo(
                        tenantJdbc, tenantAccounts);
                return commands.prepare(context, amountPEN, idempotencyKey);
            });
            if (terminal(claim.status())) return view(claim, null);

            JdbcWalletRechargeProviderRouteRegistry.Route route = providerRoutes.registerPreparing(
                    binding, context.workspaceId(), claim.rechargeId(), claim.amountMinor(), claim.currency());
            StripePaymentProvider.PaymentIntent intent = stripe.createPaymentIntent(
                    new StripePaymentProvider.PaymentIntentRequest(claim.amountMinor(), claim.currency(),
                            providerIdempotencyKey(claim.rechargeId()), Map.of(
                                    "nexa_tenant_id", context.tenantId().value().toString(),
                                    "nexa_workspace_id", context.workspaceId().value().toString(),
                                    "nexa_wallet_recharge_id", claim.rechargeId().toString())));
            requireProviderIntent(intent);

            BuyerWalletRechargeTenantCommands.Claim bound = router.inTransaction(context, tenantJdbc ->
                    walletAdapters.bindRechargeCommandsTo(tenantJdbc, customerAccounts.bindTo(tenantJdbc))
                            .bindProviderIntent(context, claim.rechargeId(), intent.providerId()));
            providerRoutes.bindProviderIntent(route, intent.providerId());
            return view(bound, intent.clientSecret());
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                | DataAccessException unavailable) {
            throw new BuyerWalletStoreUnavailableException(unavailable);
        }
    }

    @Override
    public BuyerWalletModels.RechargeView get(CurrentAccessContext context, UUID rechargeId) {
        requireBuyer(context, PermissionKey.PAYMENT_READ);
        requireCentralScope(context);
        try {
            return router.inTransaction(context, tenantJdbc ->
                    walletAdapters.bindRechargeCommandsTo(tenantJdbc, customerAccounts.bindTo(tenantJdbc))
                            .get(context, rechargeId));
        } catch (TenantBusinessDatabaseUnavailableException | DataAccessException unavailable) {
            throw new BuyerWalletStoreUnavailableException(unavailable);
        }
    }

    private static BuyerWalletModels.RechargeIntentView view(BuyerWalletRechargeTenantCommands.Claim claim,
            String clientSecret) {
        return new BuyerWalletModels.RechargeIntentView(claim.rechargeId(), claim.status(),
                BigDecimal.valueOf(claim.amountMinor(), 2), claim.currency(), "STRIPE",
                claim.providerPaymentIntentId(), clientSecret, claim.createdAt());
    }

    private static boolean terminal(BuyerWalletModels.RechargeStatus status) {
        return status == BuyerWalletModels.RechargeStatus.SUCCEEDED
                || status == BuyerWalletModels.RechargeStatus.FAILED
                || status == BuyerWalletModels.RechargeStatus.CANCELLED
                || status == BuyerWalletModels.RechargeStatus.REJECTED;
    }

    private static void requireProviderIntent(StripePaymentProvider.PaymentIntent intent) {
        if (intent == null || intent.providerId() == null || intent.providerId().isBlank()
                || intent.providerId().length() > 255 || intent.status() == null || intent.status().isBlank()
                || intent.clientSecret() == null || intent.clientSecret().isBlank()) {
            throw new IllegalStateException("Stripe returned an incomplete wallet recharge PaymentIntent");
        }
    }

    private static String providerIdempotencyKey(UUID rechargeId) {
        return "nexa-wallet-recharge-" + rechargeId;
    }

    private static void requireCentralScope(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified Buyer access context is required");
        RlsRequestScope.Scope scope = RlsRequestScope.current();
        if (scope == null || !context.tenantId().value().equals(scope.tenantId())
                || !context.workspaceId().value().equals(scope.workspaceId())) {
            throw new AccessPolicyViolation("Wallet recharge requires matching verified Tenant and Workspace scope");
        }
    }

    private static void requireBuyer(CurrentAccessContext context, PermissionKey permission) {
        if (context == null) throw new AccessPolicyViolation("Verified Buyer access context is required");
        context.requirePermission(permission);
        if (!context.hasRoleCode("BUYER")) throw new AccessPolicyViolation("An active Buyer membership is required");
    }
}
