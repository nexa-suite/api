package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.port.out.CredentialPersistencePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcCredentialPersistenceAdapter implements CredentialPersistencePort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcCredentialPersistenceAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public void changeOwnPassword(Actor actor, String currentPassword, String newPassword) { delegate.changeOwnPassword(actor, currentPassword, newPassword); }
}
