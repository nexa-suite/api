package com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset;

import java.util.Objects;
import java.util.UUID;

public record PasswordResetRequestId(UUID value) {
    public PasswordResetRequestId { Objects.requireNonNull(value, "Password reset request id is required"); }
}
