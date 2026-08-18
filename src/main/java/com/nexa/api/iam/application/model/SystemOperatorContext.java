package com.nexa.api.iam.application.model;

import java.util.Objects;

public record SystemOperatorContext(String principalId, String permission) {
    public SystemOperatorContext {
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("System operator principal is required");
        if (!"system:organizations:activate".equals(permission)) throw new IllegalArgumentException("System operator permission is invalid");
    }
}
