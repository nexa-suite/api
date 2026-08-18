package com.nexa.api.sales.application.salesorder.model;

import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.sales.domain.model.salesorder.ManualSalesOrderSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ManualSalesOrderView(String id, String number, String tenantId, String workspaceId,
                                   String clientAccountId, String createdByMembershipId,
                                   PurchaseRequestPriority priority, LocalDate requestedDeliveryDate,
                                   ManualSalesOrderSnapshot snapshot, String currency, BigDecimal total,
                                   String status, Instant createdAt, Instant updatedAt, long version,
                                   List<SalesOrderLineView> lines) {
    public ManualSalesOrderView {
        lines = List.copyOf(lines);
    }
}
