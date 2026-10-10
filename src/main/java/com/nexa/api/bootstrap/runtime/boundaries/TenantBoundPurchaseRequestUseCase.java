package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeProposalView;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestEventView;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestFilter;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** Routes Purchase Request review, changes, and reads to the same Tenant-owned store as Buyer drafts. */
public final class TenantBoundPurchaseRequestUseCase implements PurchaseRequestUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies;
    private final TenantSalesCommitmentCompositionProvider compositions;

    public TenantBoundPurchaseRequestUseCase(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider compositions) {
        this.router = java.util.Objects.requireNonNull(router);
        this.expiryPolicies = java.util.Objects.requireNonNull(expiryPolicies);
        this.compositions = java.util.Objects.requireNonNull(compositions);
    }

    @Override public SalesPage<PurchaseRequestView> list(CurrentAccessContext c, PurchaseRequestFilter filter) {
        return call(c, useCase -> useCase.list(c, filter));
    }
    @Override public PurchaseRequestView detail(CurrentAccessContext c, String id) {
        return call(c, useCase -> useCase.detail(c, id));
    }
    @Override public List<PurchaseRequestEventView> events(CurrentAccessContext c, String id) {
        return call(c, useCase -> useCase.events(c, id));
    }
    @Override public PurchaseRequestView create(CurrentAccessContext c, String account, String priority,
            LocalDate date, String delivery, String payment, String comment, List<RequestedLine> lines) {
        return call(c, useCase -> useCase.create(c, account, priority, date, delivery, payment, comment, lines));
    }
    @Override public PurchaseRequestView update(CurrentAccessContext c, String id, String priority,
            LocalDate date, String delivery, String payment, String comment, long version) {
        return call(c, useCase -> useCase.update(c, id, priority, date, delivery, payment, comment, version));
    }
    @Override public PurchaseRequestView addLine(CurrentAccessContext c, String id, String item,
            BigDecimal quantity, String unit, String notes, long version) {
        return call(c, useCase -> useCase.addLine(c, id, item, quantity, unit, notes, version));
    }
    @Override public PurchaseRequestView updateLine(CurrentAccessContext c, String id, String line,
            BigDecimal quantity, String notes, long version) {
        return call(c, useCase -> useCase.updateLine(c, id, line, quantity, notes, version));
    }
    @Override public PurchaseRequestView deleteLine(CurrentAccessContext c, String id, String line, long version) {
        return call(c, useCase -> useCase.deleteLine(c, id, line, version));
    }
    @Override public PurchaseRequestView transition(CurrentAccessContext c, String id, String action,
            String note, long version, String key) {
        return callWithExpiryPolicy(c, useCase -> useCase.transition(c, id, action, note, version, key));
    }
    @Override public MaterialChangeProposalView currentMaterialChange(CurrentAccessContext c, String id) {
        return call(c, useCase -> useCase.currentMaterialChange(c, id));
    }
    @Override public List<MaterialChangeProposalView> materialChangeHistory(CurrentAccessContext c, String id) {
        return call(c, useCase -> useCase.materialChangeHistory(c, id));
    }
    @Override public MaterialChangeProposalView proposeMaterialChange(CurrentAccessContext c, String id,
            long version, String reason, String priority, LocalDate date, String delivery, String payment,
            String comment, List<RequestedLine> lines, String key) {
        return callWithExpiryPolicy(c, useCase -> useCase.proposeMaterialChange(c, id, version, reason, priority, date,
                delivery, payment, comment, lines, key));
    }
    @Override public PurchaseRequestView acceptMaterialChange(CurrentAccessContext c, String id,
            String proposal, long version, String key) {
        return callWithExpiryPolicy(c, useCase -> useCase.acceptMaterialChange(c, id, proposal, version, key));
    }
    @Override public PurchaseRequestView rejectMaterialChange(CurrentAccessContext c, String id,
            String proposal, long version, String key) {
        return callWithExpiryPolicy(c, useCase -> useCase.rejectMaterialChange(c, id, proposal, version, key));
    }

    private <T> T call(CurrentAccessContext context,
            Function<PurchaseRequestUseCase, T> action) {
        return router.inTransaction(context, jdbc -> action.apply(compositions.bindTo(jdbc).purchaseRequests()));
    }

    private <T> T callWithExpiryPolicy(CurrentAccessContext context,
            Function<PurchaseRequestUseCase, T> action) {
        return expiryPolicies.inTransaction(context,
                jdbc -> action.apply(compositions.bindTo(jdbc).purchaseRequests()));
    }
}
