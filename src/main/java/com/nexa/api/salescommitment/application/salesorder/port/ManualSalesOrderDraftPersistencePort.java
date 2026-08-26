package com.nexa.api.salescommitment.application.salesorder.port;

import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderDraftModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;

public interface ManualSalesOrderDraftPersistencePort {
    ManualSalesOrderDraftModels.DraftView create(CurrentAccessContext context, String idempotencyKey);

    ManualSalesOrderDraftModels.DraftView get(CurrentAccessContext context, UUID draftId);

    ManualSalesOrderDraftModels.DraftView getForUpdate(CurrentAccessContext context, UUID draftId);

    ManualSalesOrderDraftModels.DraftView updateClient(CurrentAccessContext context, UUID draftId, long expectedVersion,
                                                        ManualSalesOrderDraftModels.ClientCommand command);

    ManualSalesOrderDraftModels.DraftView replaceLines(CurrentAccessContext context, UUID draftId, long expectedVersion,
                                                       List<ManualSalesOrderDraftModels.LineCommand> lines);

    ManualSalesOrderDraftModels.DraftView updateDelivery(CurrentAccessContext context, UUID draftId, long expectedVersion,
                                                         ManualSalesOrderDraftModels.DeliveryCommand command);

    ManualSalesOrderDraftModels.DraftView markCreated(CurrentAccessContext context, UUID draftId, long expectedVersion,
                                                      String salesOrderId);

    ManualSalesOrderDraftModels.DraftView abandon(CurrentAccessContext context, UUID draftId, long expectedVersion);
}
