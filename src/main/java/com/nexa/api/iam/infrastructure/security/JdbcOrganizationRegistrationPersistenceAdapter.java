package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.IamSecurityModels.RegistrationRequest;
import com.nexa.api.iam.application.port.out.OrganizationRegistrationPersistencePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.UUID;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcOrganizationRegistrationPersistenceAdapter implements OrganizationRegistrationPersistencePort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcOrganizationRegistrationPersistenceAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public Registration submit(RegistrationRequest request, String correlationId, String traceId) { return delegate.submit(request, correlationId, traceId); }
    public Registration findStatus(UUID registrationId, String statusToken) { return delegate.findStatus(registrationId, statusToken); }
}
