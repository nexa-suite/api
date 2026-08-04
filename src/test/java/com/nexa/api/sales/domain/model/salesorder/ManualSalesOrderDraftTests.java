package com.nexa.api.sales.domain.model.salesorder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualSalesOrderDraftTests {
    @Test
    void stateMachineRequiresClientItemsAndDeliveryInOrder() {
        assertThat(ManualSalesOrderDraft.status(false, false, false)).isEqualTo(ManualSalesOrderDraftStatus.DRAFT);
        assertThat(ManualSalesOrderDraft.status(true, false, false)).isEqualTo(ManualSalesOrderDraftStatus.CLIENT_COMPLETE);
        assertThat(ManualSalesOrderDraft.status(true, true, false)).isEqualTo(ManualSalesOrderDraftStatus.ITEMS_COMPLETE);
        assertThat(ManualSalesOrderDraft.status(true, true, true)).isEqualTo(ManualSalesOrderDraftStatus.READY_TO_CREATE);
    }

    @Test
    void createdAndAbandonedDraftsAreNotMutableOrReady() {
        assertThatThrownBy(() -> ManualSalesOrderDraft.requireMutable(ManualSalesOrderDraftStatus.CREATED))
                .isInstanceOf(SalesOrderInvariantViolation.class);
        assertThatThrownBy(() -> ManualSalesOrderDraft.requireMutable(ManualSalesOrderDraftStatus.ABANDONED))
                .isInstanceOf(SalesOrderInvariantViolation.class);
        assertThatThrownBy(() -> ManualSalesOrderDraft.requireReady(ManualSalesOrderDraftStatus.ITEMS_COMPLETE))
                .isInstanceOf(SalesOrderInvariantViolation.class);
    }
}
