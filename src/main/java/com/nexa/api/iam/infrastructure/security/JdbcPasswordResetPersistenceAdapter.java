package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.port.out.PasswordResetPersistencePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcPasswordResetPersistenceAdapter implements PasswordResetPersistencePort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcPasswordResetPersistenceAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public String request(String email, String surface, String clientAddress, String correlationId, String traceId) { return delegate.request(email, surface, clientAddress, correlationId, traceId); }
    public void complete(String token, String newPassword, String correlationId, String traceId) { delegate.complete(token, newPassword, correlationId, traceId); }
}
