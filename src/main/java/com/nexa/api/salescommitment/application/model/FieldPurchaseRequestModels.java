package com.nexa.api.salescommitment.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class FieldPurchaseRequestModels {
    private FieldPurchaseRequestModels() { }
    public record Command(String clientAccountId, String priority, LocalDate requestedDeliveryDate,
                          String deliveryProfileSnapshot, String paymentOption, String comment, List<Line> lines) {
        public Command { lines = lines == null ? List.of() : List.copyOf(lines); }
    }
    public record Line(String catalogItemId, BigDecimal quantity, String unit, String notes,
                       BigDecimal expectedUnitPrice, String expectedCurrency) { }
}
