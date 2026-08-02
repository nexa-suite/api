package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.IamSecurityModels.RegistrationRequest;
import com.nexa.api.iam.application.model.SystemOperatorContext;
import com.nexa.api.iam.application.port.in.ActivateOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.GetOrganizationRegistrationStatusQuery;
import com.nexa.api.iam.application.port.in.RejectOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.SubmitOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.out.IamSecurityRepository;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class OrganizationRegistrationService implements SubmitOrganizationRegistrationCommand,
        GetOrganizationRegistrationStatusQuery, ActivateOrganizationRegistrationCommand, RejectOrganizationRegistrationCommand {
    private final IamSecurityRepository repository;

    public OrganizationRegistrationService(IamSecurityRepository repository) { this.repository = repository; }

    @Override
    @Transactional
    public Registration submit(RegistrationRequest request, String correlationId, String traceId) {
        return repository.submitRegistration(request, correlationId, traceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Registration get(UUID registrationId, String statusToken) {
        return repository.registration(registrationId, statusToken);
    }

    @Override
    @Transactional
    public Activation activate(UUID registrationId, SystemOperatorContext operator, String correlationId, String traceId) {
        return repository.activate(registrationId, operator, correlationId, traceId);
    }

    @Override
    @Transactional
    public Registration reject(UUID registrationId, SystemOperatorContext operator, String reason, String correlationId, String traceId) {
        return repository.reject(registrationId, operator, reason, correlationId, traceId);
    }
}
