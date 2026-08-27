package com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset;

import java.time.Instant;
import java.util.Objects;

public record PasswordResetExpiry(Instant value) {
    public PasswordResetExpiry { Objects.requireNonNull(value, "Password reset expiry is required"); }
    public boolean hasExpiredAt(Instant now) { return !value.isAfter(Objects.requireNonNull(now)); }
}
