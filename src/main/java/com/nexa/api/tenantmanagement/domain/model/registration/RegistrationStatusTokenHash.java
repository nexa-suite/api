package com.nexa.api.tenantmanagement.domain.model.registration;

public record RegistrationStatusTokenHash(String value) {
    public RegistrationStatusTokenHash {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Registration status token hash must be SHA-256 hex");
        value = value.toLowerCase(java.util.Locale.ROOT);
    }
}
