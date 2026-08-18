package com.nexa.api.tenantmanagement.domain.model.registration;

import java.util.Locale;

public record WorkspaceSlug(String value) {
    public WorkspaceSlug {
        if (value == null || !value.matches("[A-Za-z0-9-]{3,80}")) throw new IllegalArgumentException("Workspace slug is invalid");
        value = value.trim().toLowerCase(Locale.ROOT);
    }
}
