package com.nexa.api.salescommitment.application.buyerrequest.model;

import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption;

import java.time.LocalDate;
import java.util.List;

public record CreateBuyerRequestCommand(String clientAccountId, String addressId, Address manualAddress,
                                        LocalDate requestedDeliveryDate, String deliveryNotes,
                                        String warehouseId, String routeProvider, PaymentOption paymentOption,
                                        String comments, List<Line> lines) {
    public CreateBuyerRequestCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public record Line(String catalogItemId, java.math.BigDecimal quantity, String unit, String notes) { }
}
