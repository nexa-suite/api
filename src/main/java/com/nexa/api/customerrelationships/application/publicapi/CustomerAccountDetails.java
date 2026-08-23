package com.nexa.api.customerrelationships.application.publicapi;

import java.math.BigDecimal;

/** Data-only customer snapshot for synchronous consumers; it carries no management behavior. */
public record CustomerAccountDetails(
        String id,
        String code,
        String businessName,
        String commercialName,
        String taxIdentifierType,
        String taxIdentifierValue,
        String paymentCondition,
        BigDecimal creditLimit,
        String creditCurrency,
        BigDecimal currentCommercialExposure,
        BigDecimal availableCredit,
        String status) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
