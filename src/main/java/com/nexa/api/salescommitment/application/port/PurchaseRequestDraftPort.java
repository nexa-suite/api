package com.nexa.api.salescommitment.application.port;

import com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PurchaseRequestDraftPort {
    PurchaseRequestDraftModels.DraftView create(CurrentAccessContext context, UUID clientAccountId, LocalDate requestedDeliveryDate);
    PurchaseRequestDraftModels.DraftView get(CurrentAccessContext context, UUID draftId);
    PurchaseRequestDraftModels.DraftView replaceLines(CurrentAccessContext context, UUID draftId, long expectedVersion, List<LineCommand> commands);
    PurchaseRequestDraftModels.DraftView setDestination(CurrentAccessContext context, UUID draftId, long expectedVersion, UUID addressId);
    PurchaseRequestDraftModels.DraftView previewRoute(CurrentAccessContext context, UUID draftId, long expectedVersion, String provider);
    PurchaseRequestDraftModels.DraftView setPreferences(CurrentAccessContext context, UUID draftId, long expectedVersion, String paymentPreference, LocalDate requestedDeliveryDate);
    PurchaseRequestDraftModels.ReviewView review(CurrentAccessContext context, UUID draftId);
    PurchaseRequestDraftModels.DraftView submit(CurrentAccessContext context, UUID draftId, long expectedVersion, String idempotencyKey);

    record LineCommand(UUID skuId, BigDecimal quantity, String unit, String notes) { }
}
