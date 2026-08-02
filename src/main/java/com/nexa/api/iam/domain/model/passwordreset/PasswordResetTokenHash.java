package com.nexa.api.iam.domain.model.passwordreset;

import java.util.Objects;

public record PasswordResetTokenHash(String value) {
    public PasswordResetTokenHash {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Password reset token hash must be SHA-256 hex");
        value = value.toLowerCase(java.util.Locale.ROOT);
    }
}
