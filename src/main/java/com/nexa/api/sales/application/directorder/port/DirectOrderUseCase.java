package com.nexa.api.sales.application.directorder.port;

import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface DirectOrderUseCase {
    SalesOrderView create(CurrentAccessContext context, String clientAccountId, String priority,
                          LocalDate requestedDeliveryDate, String deliverySnapshot, String paymentOption,
                          String notes, List<Line> lines, String idempotencyKey);

    record Line(String catalogItemId, BigDecimal quantity, String unit) { }
}
