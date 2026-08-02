package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.port.in.RequestPasswordResetCommand;
import com.nexa.api.iam.application.port.in.ResetPasswordCommand;
import com.nexa.api.iam.application.port.out.IamSecurityRepository;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetService implements RequestPasswordResetCommand, ResetPasswordCommand {
    private final IamSecurityRepository repository;

    public PasswordResetService(IamSecurityRepository repository) { this.repository = repository; }

    @Override
    @Transactional
    public String request(String email, String surface, String clientAddress, String correlationId, String traceId) {
        return repository.requestPasswordReset(email, surface, clientAddress, correlationId, traceId);
    }

    @Override
    @Transactional
    public void reset(String token, String newPassword, String correlationId, String traceId) {
        repository.resetPassword(token, newPassword, correlationId, traceId);
    }
}
