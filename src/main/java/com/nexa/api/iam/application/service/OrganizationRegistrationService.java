package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.IamSecurityModels.RegistrationRequest;
import com.nexa.api.iam.application.model.SystemOperatorContext;
import com.nexa.api.iam.application.port.in.ActivateOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.GetOrganizationRegistrationStatusQuery;
import com.nexa.api.iam.application.port.in.RejectOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.SubmitOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.out.OrganizationActivationPersistencePort;
import com.nexa.api.iam.application.port.out.OrganizationRegistrationPersistencePort;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Objects;
import com.nexa.api.tenantmanagement.domain.model.registration.FounderIdentity;
import com.nexa.api.tenantmanagement.domain.model.registration.OrganizationRegistration;
import com.nexa.api.tenantmanagement.domain.model.registration.OrganizationRegistrationId;
import com.nexa.api.tenantmanagement.domain.model.registration.ReferencePlan;
import com.nexa.api.tenantmanagement.domain.model.registration.RegistrationStatusTokenHash;
import com.nexa.api.tenantmanagement.domain.model.registration.TermsAcceptance;
import com.nexa.api.tenantmanagement.domain.model.registration.WorkspaceSlug;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class OrganizationRegistrationService implements SubmitOrganizationRegistrationCommand,
        GetOrganizationRegistrationStatusQuery, ActivateOrganizationRegistrationCommand, RejectOrganizationRegistrationCommand {
    private final OrganizationRegistrationPersistencePort registrations;
    private final OrganizationActivationPersistencePort activations;

    public OrganizationRegistrationService(OrganizationRegistrationPersistencePort registrations,
            OrganizationActivationPersistencePort activations) {
        this.registrations = registrations; this.activations = activations;
    }

    @Override
    @Transactional
    public Registration submit(RegistrationRequest request, String correlationId, String traceId) {
        Objects.requireNonNull(request, "Registration request is required");
        OrganizationRegistration.submit(new OrganizationRegistrationId(UUID.randomUUID()),
                new FounderIdentity(request.founderEmail(), request.founderDisplayName()),
                new WorkspaceSlug(request.workspaceSlug()), new TermsAcceptance(request.termsVersion(), request.termsAccepted()),
                ReferencePlan.valueOf(request.referencePlan()), new RegistrationStatusTokenHash("0".repeat(64)));
        return registrations.submit(request, correlationId, traceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Registration get(UUID registrationId, String statusToken) {
        return registrations.findStatus(registrationId, statusToken);
    }

    @Override
    @Transactional
    public Activation activate(UUID registrationId, SystemOperatorContext operator, String correlationId, String traceId) {
        requireOperator(operator);
        return activations.activate(registrationId, operator, correlationId, traceId);
    }

    @Override
    @Transactional
    public Registration reject(UUID registrationId, SystemOperatorContext operator, String reason, String correlationId, String traceId) {
        requireOperator(operator);
        return activations.reject(registrationId, operator, reason, correlationId, traceId);
    }

    private static void requireOperator(SystemOperatorContext operator) {
        if (operator == null || !"system:organizations:activate".equals(operator.permission())) throw new IllegalArgumentException("System operator principal is required");
    }
}
