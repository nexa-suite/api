package com.nexa.api.salescommitment.domain.model.salesorder;

public enum ManualSalesOrderDraftStatus {
    DRAFT,
    CLIENT_COMPLETE,
    ITEMS_COMPLETE,
    DELIVERY_COMPLETE,
    READY_TO_CREATE,
    CREATED,
    ABANDONED
}
