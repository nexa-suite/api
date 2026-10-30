package com.nexa.api.salescommitment.application.directorder.port;

import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@NamedInterface(value = "sales-public", propagate = false)
public interface DirectOrderUseCase {
    DirectOrderDeliveryPolicy deliveryPolicy(CurrentAccessContext context);

    SalesOrderView create(CurrentAccessContext context, String clientAccountId, String priority,
                          LocalDate requestedDeliveryDate, String deliverySnapshot, String paymentOption,
                          String notes, List<Line> lines, String idempotencyKey);

    @NamedInterface(value = "sales-public", propagate = false)
    record DirectOrderDeliveryPolicy(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Fixed global minimum expressed in Monday–Friday business days; field name retained for Operations compatibility.",
                    example = "5") int minimumDeliveryLeadDays,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Earliest date computed using America/Lima and the five-business-day global policy.")
            LocalDate earliestAllowedDeliveryDate) { }

    @NamedInterface(value = "sales-public", propagate = false)
    record Line(String catalogItemId, BigDecimal quantity, String unit) { }
}
