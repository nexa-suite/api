package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.port.out.OpaqueSecurityTokenPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcOpaqueSecurityTokenAdapter implements OpaqueSecurityTokenPort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcOpaqueSecurityTokenAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public String generate() { return delegate.generate(); }
    public String sha256(String value) { return delegate.sha256(value); }
}
