package com.nexa.api.customerbuyerrelationships.application.publicapi;

/** Data-only customer snapshot for synchronous consumers; it carries no management behavior. */
public record CustomerAccountDetails(
        String id,
        String code,
        String businessName,
        String commercialName,
        String taxIdentifierType,
        String taxIdentifierValue,
        String segment,
        String status) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
