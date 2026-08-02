package com.nexa.api.tenantmanagement.domain.model.registration;

import java.util.Objects;

public record TermsAcceptance(String version, boolean accepted) {
    public TermsAcceptance {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Terms version is required");
        if (!accepted) throw new IllegalArgumentException("Terms must be accepted");
        version = version.trim();
    }
}
