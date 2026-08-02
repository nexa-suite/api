package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.port.in.ChangeOwnPasswordCommand;
import com.nexa.api.iam.application.port.out.IamSecurityRepository;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordSecurityService implements ChangeOwnPasswordCommand {
    private final IamSecurityRepository repository;

    public PasswordSecurityService(IamSecurityRepository repository) { this.repository = repository; }

    @Override
    @Transactional
    public void change(Actor actor, String currentPassword, String newPassword) {
        repository.changePassword(actor, currentPassword, newPassword);
    }
}
