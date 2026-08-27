package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.registration;

import java.util.Locale;
import java.util.Objects;

public record FounderIdentity(String email, String displayName) {
    public FounderIdentity {
        if (email == null || !email.contains("@")) throw new IllegalArgumentException("Founder email is invalid");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Founder display name is required");
        email = email.trim().toLowerCase(Locale.ROOT); displayName = displayName.trim();
    }
}
