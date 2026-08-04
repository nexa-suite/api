package com.nexa.api.sales.domain.model.salesorder;

public enum ManualSalesOrderDraftStatus {
    DRAFT,
    CLIENT_COMPLETE,
    ITEMS_COMPLETE,
    DELIVERY_COMPLETE,
    READY_TO_CREATE,
    CREATED,
    ABANDONED
}
