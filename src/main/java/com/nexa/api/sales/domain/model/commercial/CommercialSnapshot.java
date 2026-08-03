package com.nexa.api.sales.domain.model.commercial;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.credit.CreditProfile;

import java.util.Objects;

public record CommercialSnapshot(String clientAccountId, String businessName, String commercialName,
                                 String taxIdentifier, CreditProfile credit, PaymentTerms paymentTerms,
                                 boolean active) {
    public CommercialSnapshot {
        clientAccountId = required(clientAccountId, "Client account id", 64);
        businessName = required(businessName, "Business name", 200);
        commercialName = required(commercialName, "Commercial name", 200);
        taxIdentifier = required(taxIdentifier, "Tax identifier", 64);
        credit = Objects.requireNonNull(credit, "Credit profile is required");
        paymentTerms = Objects.requireNonNull(paymentTerms, "Payment terms are required");
        if (!active) throw new SalesInvariantViolation("Inactive client account cannot create an order");
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
