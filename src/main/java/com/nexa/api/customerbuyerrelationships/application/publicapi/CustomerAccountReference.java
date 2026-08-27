package com.nexa.api.customerbuyerrelationships.application.publicapi;

/** Stable cross-context reference. Customer lifecycle and mutable details stay internal. */
public record CustomerAccountReference(String id, String status) {
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
