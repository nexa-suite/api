package com.nexa.api.sales.application.salesorder.port;

import com.nexa.api.sales.application.salesorder.model.ManualSalesOrderDraftModels;
import com.nexa.api.sales.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;

public interface ManualSalesOrderDraftUseCase {
    ManualSalesOrderDraftModels.DraftView create(CurrentAccessContext context, String idempotencyKey);
    ManualSalesOrderDraftModels.DraftView get(CurrentAccessContext context, UUID draftId);
    ManualSalesOrderDraftModels.DraftView updateClient(CurrentAccessContext context, UUID draftId, long version, ManualSalesOrderDraftModels.ClientCommand command);
    ManualSalesOrderDraftModels.DraftView replaceLines(CurrentAccessContext context, UUID draftId, long version, List<ManualSalesOrderDraftModels.LineCommand> lines);
    ManualSalesOrderDraftModels.DraftView updateDelivery(CurrentAccessContext context, UUID draftId, long version, ManualSalesOrderDraftModels.DeliveryCommand command);
    ManualSalesOrderDraftModels.ReviewView review(CurrentAccessContext context, UUID draftId);
    ManualSalesOrderView submit(CurrentAccessContext context, UUID draftId, long version, String idempotencyKey);
    ManualSalesOrderDraftModels.DraftView abandon(CurrentAccessContext context, UUID draftId, long version);
}
