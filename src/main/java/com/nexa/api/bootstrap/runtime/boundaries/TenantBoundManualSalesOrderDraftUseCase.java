package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderDraftModels;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Runs every resumable manual-order operation on one Tenant transaction. */
public final class TenantBoundManualSalesOrderDraftUseCase implements ManualSalesOrderDraftUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantSalesCommitmentCompositionProvider compositions;

    public TenantBoundManualSalesOrderDraftUseCase(TenantBusinessDatabaseRouter router,
            TenantSalesCommitmentCompositionProvider compositions) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.compositions = Objects.requireNonNull(compositions, "Tenant Sales composition provider is required");
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView create(CurrentAccessContext context, String idempotencyKey) {
        return call(context, drafts -> drafts.create(context, idempotencyKey));
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView get(CurrentAccessContext context, UUID draftId) {
        return call(context, drafts -> drafts.get(context, draftId));
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView updateClient(CurrentAccessContext context, UUID draftId,
            long version, ManualSalesOrderDraftModels.ClientCommand command) {
        return call(context, drafts -> drafts.updateClient(context, draftId, version, command));
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView replaceLines(CurrentAccessContext context, UUID draftId,
            long version, List<ManualSalesOrderDraftModels.LineCommand> lines) {
        return call(context, drafts -> drafts.replaceLines(context, draftId, version, lines));
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView updateDelivery(CurrentAccessContext context, UUID draftId,
            long version, ManualSalesOrderDraftModels.DeliveryCommand command) {
        return call(context, drafts -> drafts.updateDelivery(context, draftId, version, command));
    }

    @Override
    public ManualSalesOrderDraftModels.ReviewView review(CurrentAccessContext context, UUID draftId) {
        return call(context, drafts -> drafts.review(context, draftId));
    }

    @Override
    public ManualSalesOrderView submit(CurrentAccessContext context, UUID draftId, long version,
            String idempotencyKey) {
        return call(context, drafts -> drafts.submit(context, draftId, version, idempotencyKey));
    }

    @Override
    public ManualSalesOrderDraftModels.DraftView abandon(CurrentAccessContext context, UUID draftId, long version) {
        return call(context, drafts -> drafts.abandon(context, draftId, version));
    }

    private <T> T call(CurrentAccessContext context, Function<ManualSalesOrderDraftUseCase, T> action) {
        return router.inTransaction(context,
                jdbc -> action.apply(compositions.bindTo(jdbc).manualSalesOrderDrafts()));
    }
}
