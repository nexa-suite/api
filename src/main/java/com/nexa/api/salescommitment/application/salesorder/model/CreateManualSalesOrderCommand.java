package com.nexa.api.salescommitment.application.salesorder.model;

import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

@NamedInterface(value = "sales-public", propagate = false)
public record CreateManualSalesOrderCommand(String clientAccountId, String addressId, Address manualAddress,
                                            LocalDate requestedDeliveryDate, String deliveryNotes,
                                            String warehouseId, String routeProvider, PaymentOption paymentOption,
                                            PurchaseRequestPriority priority, String currency, String notes,
                                            List<Line> lines) {
    public CreateManualSalesOrderCommand {
        priority = priority == null ? PurchaseRequestPriority.NORMAL : priority;
        currency = currency == null ? "PEN" : currency;
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    @NamedInterface(value = "sales-public", propagate = false)
    public record Line(UUID skuId, String catalogItemId, java.math.BigDecimal quantity, String unit, String notes) {
        public Line(String catalogItemId, java.math.BigDecimal quantity, String unit, String notes) {
            this(null, catalogItemId, quantity, unit, notes);
        }
    }
}
