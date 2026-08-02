package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.port.out.PasswordResetThrottlePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcPasswordResetThrottleAdapter implements PasswordResetThrottlePort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcPasswordResetThrottleAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public long recordAttempt(String normalizedIdentifier, String clientAddress) { return delegate.recordAttempt(normalizedIdentifier, clientAddress); }
}
