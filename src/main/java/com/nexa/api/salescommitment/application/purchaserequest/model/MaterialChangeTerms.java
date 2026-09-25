package com.nexa.api.salescommitment.application.purchaserequest.model;

import java.time.LocalDate;
import java.util.List;

/** Server-priced snapshot of the complete commercial terms proposed for a Purchase Request. */
public record MaterialChangeTerms(String priority, LocalDate requestedDeliveryDate,
        String deliveryProfileSnapshot, String paymentOption, String comment,
        List<PurchaseRequestLineView> lines) {
    public MaterialChangeTerms {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}
