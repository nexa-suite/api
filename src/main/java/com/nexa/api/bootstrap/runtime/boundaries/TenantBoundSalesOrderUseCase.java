package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.function.Function;

/** Routes Sales Order reads, transitions, and PR conversion through the Tenant transaction. */
public final class TenantBoundSalesOrderUseCase implements SalesOrderUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies;
    private final TenantSalesCommitmentCompositionProvider compositions;

    public TenantBoundSalesOrderUseCase(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider compositions) {
        this.router = java.util.Objects.requireNonNull(router);
        this.expiryPolicies = java.util.Objects.requireNonNull(expiryPolicies);
        this.compositions = java.util.Objects.requireNonNull(compositions);
    }

    @Override public SalesOrderView convert(CurrentAccessContext c, String request, long version,
            String key, String note) {
        return expiryPolicies.inTransaction(c,
                jdbc -> compositions.bindTo(jdbc).salesOrders().convert(c, request, version, key, note));
    }
    @Override public SalesPage<SalesOrderView> list(CurrentAccessContext c, SalesOrderFilter filter) {
        return call(c, useCase -> useCase.list(c, filter));
    }
    @Override public SalesOrderView detail(CurrentAccessContext c, String id) {
        return call(c, useCase -> useCase.detail(c, id));
    }
    @Override public SalesOrderView transition(CurrentAccessContext c, String id, String action,
            String reason, long version) {
        return call(c, useCase -> useCase.transition(c, id, action, reason, version));
    }
    @Override public SalesOrderView transition(CurrentAccessContext c, String id, String action,
            String reason, long version, String key) {
        return call(c, useCase -> useCase.transition(c, id, action, reason, version, key));
    }
    @Override public List<SalesOrderEventView> events(CurrentAccessContext c, String id) {
        return call(c, useCase -> useCase.events(c, id));
    }
    @Override public SalesPage<FulfillmentCandidateView> fulfillmentCandidates(CurrentAccessContext c,
            SalesOrderFilter filter) {
        return call(c, useCase -> useCase.fulfillmentCandidates(c, filter));
    }

    private <T> T call(CurrentAccessContext context, Function<com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase, T> action) {
        return router.inTransaction(context, jdbc -> action.apply(compositions.bindTo(jdbc).salesOrders()));
    }
}
