package com.nexa.api.customerrelationships.application.publicapi;

/** Stable cross-context reference. Customer lifecycle and mutable details stay internal. */
public record CustomerAccountReference(String id, String status) {
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
