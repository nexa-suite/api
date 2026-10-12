package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.salescommitment.application.port.PurchaseRequestDraftPort;
import com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Routes every Purchase Request draft operation through one validated Tenant transaction. */
public final class TenantBoundPurchaseRequestDraftPort implements PurchaseRequestDraftPort {
    private final TenantBusinessDatabaseRouter router;
    private final TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies;
    private final TenantSalesCommitmentCompositionProvider compositions;

    public TenantBoundPurchaseRequestDraftPort(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider compositions) {
        this.router = java.util.Objects.requireNonNull(router);
        this.expiryPolicies = java.util.Objects.requireNonNull(expiryPolicies);
        this.compositions = java.util.Objects.requireNonNull(compositions);
    }

    @Override public PurchaseRequestDraftModels.DraftView create(CurrentAccessContext c, UUID account, LocalDate date) {
        return call(c, composition -> composition.purchaseRequestDrafts().create(c, account, date));
    }
    @Override public PurchaseRequestDraftModels.DraftPage list(CurrentAccessContext c, int page, int size) {
        return call(c, composition -> composition.purchaseRequestDrafts().list(c, page, size));
    }
    @Override public PurchaseRequestDraftModels.DraftView get(CurrentAccessContext c, UUID id) {
        return call(c, composition -> composition.purchaseRequestDrafts().get(c, id));
    }
    @Override public PurchaseRequestDraftModels.DraftView replaceLines(CurrentAccessContext c, UUID id,
            long version, List<LineCommand> commands) {
        return call(c, composition -> composition.purchaseRequestDrafts().replaceLines(c, id, version, commands));
    }
    @Override public PurchaseRequestDraftModels.DraftView setDestination(CurrentAccessContext c, UUID id,
            long version, UUID addressId) {
        return call(c, composition -> composition.purchaseRequestDrafts().setDestination(c, id, version, addressId));
    }
    @Override public PurchaseRequestDraftModels.DraftView previewRoute(CurrentAccessContext c, UUID id,
            long version, String provider) {
        return call(c, composition -> composition.purchaseRequestDrafts().previewRoute(c, id, version, provider));
    }
    @Override public PurchaseRequestDraftModels.DraftView setPreferences(CurrentAccessContext c, UUID id,
            long version, String payment, LocalDate date) {
        return call(c, composition -> composition.purchaseRequestDrafts().setPreferences(c, id, version, payment, date));
    }
    @Override public PurchaseRequestDraftModels.ReviewView review(CurrentAccessContext c, UUID id) {
        return call(c, composition -> composition.purchaseRequestDrafts().review(c, id));
    }
    @Override public PurchaseRequestDraftModels.DraftView submit(CurrentAccessContext c, UUID id,
            long version, String key) {
        return expiryPolicies.inTransaction(c, jdbc ->
                compositions.bindTo(jdbc).purchaseRequestDrafts().submit(c, id, version, key));
    }

    private <T> T call(CurrentAccessContext context,
            Function<TenantSalesCommitmentCompositionFactory.Composition, T> action) {
        return router.inTransaction(context, jdbc -> action.apply(compositions.bindTo(jdbc)));
    }
}
