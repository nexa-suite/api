package com.nexa.api.salescommitment.application.directorder.port;

import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@NamedInterface(value = "sales-public", propagate = false)
public interface DirectOrderUseCase {
    SalesOrderView create(CurrentAccessContext context, String clientAccountId, String priority,
                          LocalDate requestedDeliveryDate, String deliverySnapshot, String paymentOption,
                          String notes, List<Line> lines, String idempotencyKey);

    @NamedInterface(value = "sales-public", propagate = false)
    record Line(String catalogItemId, BigDecimal quantity, String unit) { }
}
