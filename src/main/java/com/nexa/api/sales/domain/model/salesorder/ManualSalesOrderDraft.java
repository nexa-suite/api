package com.nexa.api.sales.domain.model.salesorder;

import java.util.Objects;

/** Domain state machine for a resumable four-step Sales-owned manual order. */
public final class ManualSalesOrderDraft {
    private ManualSalesOrderDraft() { }

    public static ManualSalesOrderDraftStatus status(boolean client, boolean items, boolean delivery) {
        if (!client) return ManualSalesOrderDraftStatus.DRAFT;
        if (!items) return ManualSalesOrderDraftStatus.CLIENT_COMPLETE;
        if (!delivery) return ManualSalesOrderDraftStatus.ITEMS_COMPLETE;
        return ManualSalesOrderDraftStatus.READY_TO_CREATE;
    }

    public static void requireMutable(ManualSalesOrderDraftStatus status) {
        Objects.requireNonNull(status, "Manual order draft status is required");
        if (status == ManualSalesOrderDraftStatus.CREATED || status == ManualSalesOrderDraftStatus.ABANDONED) {
            throw new SalesOrderInvariantViolation("Manual sales order draft is no longer mutable");
        }
    }

    public static void requireReady(ManualSalesOrderDraftStatus status) {
        if (status != ManualSalesOrderDraftStatus.READY_TO_CREATE) {
            throw new SalesOrderInvariantViolation("Manual sales order draft is not ready to create");
        }
    }
}
