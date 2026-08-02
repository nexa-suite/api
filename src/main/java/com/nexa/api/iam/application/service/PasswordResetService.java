package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.port.in.RequestPasswordResetCommand;
import com.nexa.api.iam.application.port.in.ResetPasswordCommand;
import com.nexa.api.iam.application.port.out.PasswordResetPersistencePort;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import com.nexa.api.iam.domain.model.password.PasswordPolicy;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetService implements RequestPasswordResetCommand, ResetPasswordCommand {
    private final PasswordResetPersistencePort repository;

    public PasswordResetService(PasswordResetPersistencePort repository) { this.repository = repository; }

    @Override
    @Transactional
    public String request(String email, String surface, String clientAddress, String correlationId, String traceId) {
        if (email == null || email.isBlank() || surface == null || surface.isBlank()) throw new IllegalArgumentException("Reset request is invalid");
        return repository.request(email, surface, clientAddress, correlationId, traceId);
    }

    @Override
    @Transactional
    public void reset(String token, String newPassword, String correlationId, String traceId) {
        if (token == null || token.isBlank() || !PasswordPolicy.isValid(newPassword)) throw new IllegalArgumentException("Reset request is invalid");
        repository.complete(token, newPassword, correlationId, traceId);
    }
}
