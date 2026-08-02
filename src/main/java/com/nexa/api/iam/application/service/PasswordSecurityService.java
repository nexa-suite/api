package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.port.in.ChangeOwnPasswordCommand;
import com.nexa.api.iam.application.port.out.CredentialPersistencePort;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import com.nexa.api.iam.domain.model.password.PasswordPolicy;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordSecurityService implements ChangeOwnPasswordCommand {
    private final CredentialPersistencePort repository;

    public PasswordSecurityService(CredentialPersistencePort repository) { this.repository = repository; }

    @Override
    @Transactional
    public void change(Actor actor, String currentPassword, String newPassword) {
        if (!PasswordPolicy.isValid(newPassword)) throw new IllegalArgumentException("Password policy is invalid");
        repository.changeOwnPassword(java.util.Objects.requireNonNull(actor, "Verified actor is required"), currentPassword, newPassword);
    }
}
