package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotConflictException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterUnavailableException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.payments.application.publicapi.BuyerWalletStoreUnavailableException;
import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.publicapi.BuyerWalletReadPort;
import com.nexa.api.payments.tenantdatabase.BuyerWalletTenantDatabaseAdapterFactory;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.UUID;

/** Composes the local BC-02 relationship check and BC-08 read on one routed Tenant session. */
public final class TenantBoundBuyerWalletReadPort implements BuyerWalletReadPort {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final BuyerWalletTenantDatabaseAdapterFactory walletAdapters;
    private final TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies;
    private final TenantSalesCommitmentCompositionProvider salesCommitments;
    private final MeterRegistry metrics;

    public TenantBoundBuyerWalletReadPort(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts,
            BuyerWalletTenantDatabaseAdapterFactory walletAdapters) {
        this(router, customerAccounts, walletAdapters, null, null);
    }

    public TenantBoundBuyerWalletReadPort(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts,
            BuyerWalletTenantDatabaseAdapterFactory walletAdapters,
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider salesCommitments) {
        this(router, customerAccounts, walletAdapters, expiryPolicies, salesCommitments, null);
    }

    public TenantBoundBuyerWalletReadPort(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts,
            BuyerWalletTenantDatabaseAdapterFactory walletAdapters,
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider salesCommitments,
            MeterRegistry metrics) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts,
                "Tenant Customer Account query factory is required");
        this.walletAdapters = Objects.requireNonNull(walletAdapters,
                "Tenant wallet adapter factory is required");
        this.expiryPolicies = expiryPolicies;
        this.salesCommitments = salesCommitments;
        this.metrics = metrics;
    }

    @Override
    public BuyerWalletModels.Snapshot read(CurrentAccessContext context, UUID humanIdentityId, int page, int size) {
        requireVerifiedScope(context, humanIdentityId);
        try {
            return router.inTransaction(context, tenantJdbc -> readWithinTenant(
                    context, humanIdentityId, page, size, tenantJdbc));
        } catch (TenantBusinessDatabaseUnavailableException | DataAccessException unavailable) {
            throw new BuyerWalletStoreUnavailableException(unavailable);
        }
    }

    @Override
    public boolean orderPaymentSupported(CurrentAccessContext context) {
        if (context == null) return unsupported("missing_context");
        if (salesCommitments == null) return unsupported("composition_unavailable");
        if (!salesCommitments.walletOrderPaymentEnabled()) return unsupported("feature_disabled");
        if (expiryPolicies == null) return unsupported("policy_resolver_unavailable");
        try {
            requireVerifiedScope(context, context.userId().value());
            expiryPolicies.verifyWalletCapabilityReady(context);
            return expiryPolicies.inTransaction(context, tenantJdbc -> {
                Objects.requireNonNull(salesCommitments.bindTo(tenantJdbc),
                        "Tenant Sales Commitment composition provider returned no composition");
                return true;
            });
        } catch (AccessPolicyViolation invalidScope) {
            return unsupported("verified_scope_unavailable");
        } catch (TenantBusinessDatabaseUnavailableException unavailableBinding) {
            return unsupported("tenant_binding_unavailable");
        } catch (TenantBusinessDatabasePolicySnapshotConflictException stalePolicy) {
            return unsupported("policy_snapshot_unready");
        } catch (TenantBusinessDatabasePolicySnapshotWriterUnavailableException unavailableWriter) {
            return unsupported("policy_writer_unavailable");
        } catch (TenantBusinessDatabaseSchemaManifestMismatchException staleSchemaManifest) {
            return unsupported("schema_manifest_mismatch");
        } catch (CannotGetJdbcConnectionException | CannotCreateTransactionException unavailableDatabase) {
            return unsupported("tenant_database_unavailable");
        }
    }

    private boolean unsupported(String reason) {
        if (metrics != null) {
            metrics.counter("nexa.buyer.wallet.capability", "feature", "order_payment",
                    "outcome", "unavailable", "reason", reason).increment();
        }
        return false;
    }

    private BuyerWalletModels.Snapshot readWithinTenant(CurrentAccessContext context, UUID humanIdentityId,
            int page, int size, JdbcTemplate tenantJdbc) {
        CustomerAccountQuery tenantAccountQuery = Objects.requireNonNull(customerAccounts.bindTo(tenantJdbc),
                "Tenant Customer Account query factory returned no query");
        CustomerAccountReference relationship = tenantAccountQuery.findBuyerReference(
                        context.tenantId().toString(), context.workspaceId().toString(), context.membershipId().toString())
                .filter(CustomerAccountReference::active)
                .orElseThrow(() -> new AccessPolicyViolation("An active Buyer account relationship is required"));
        if (relationship.id() == null || relationship.id().isBlank()) {
            throw new AccessPolicyViolation("An active Buyer account relationship is required");
        }
        return walletAdapters.bindReadTo(tenantJdbc).read(context, humanIdentityId, page, size);
    }

    private static void requireVerifiedScope(CurrentAccessContext context, UUID humanIdentityId) {
        Objects.requireNonNull(context, "Verified Buyer access context is required");
        Objects.requireNonNull(humanIdentityId, "Current Buyer identity is required");
        context.requirePermission(PermissionKey.PAYMENT_READ);
        if (!context.userId().value().equals(humanIdentityId)) {
            throw new AccessPolicyViolation("Buyer wallet identity must match the current Buyer");
        }
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        RlsRequestScope.Scope current = RlsRequestScope.current();
        if (current == null || !tenantId.equals(current.tenantId()) || !workspaceId.equals(current.workspaceId())) {
            throw new AccessPolicyViolation("Buyer wallet requires the matching verified Tenant and Workspace scope");
        }
    }
}
