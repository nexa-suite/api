package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.SystemOperatorContext;
import com.nexa.api.iam.application.port.out.OrganizationActivationPersistencePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.UUID;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcOrganizationActivationAdapter implements OrganizationActivationPersistencePort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcOrganizationActivationAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public Activation activate(UUID id, SystemOperatorContext operator, String correlationId, String traceId) { return delegate.activate(id, operator, correlationId, traceId); }
    public Registration reject(UUID id, SystemOperatorContext operator, String reason, String correlationId, String traceId) { return delegate.reject(id, operator, reason, correlationId, traceId); }
}
