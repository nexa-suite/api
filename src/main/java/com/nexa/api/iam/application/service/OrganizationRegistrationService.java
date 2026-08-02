package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.exception.IamSecurityException;
import com.nexa.api.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.IamSecurityModels.RegistrationRequest;
import com.nexa.api.iam.application.model.SystemOperatorContext;
import com.nexa.api.iam.application.port.in.ActivateOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.GetOrganizationRegistrationStatusQuery;
import com.nexa.api.iam.application.port.in.RejectOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.SubmitOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.out.MembershipRolePersistencePort;
import com.nexa.api.iam.application.port.out.OpaqueSecurityTokenPort;
import com.nexa.api.iam.application.port.out.OrganizationActivationPersistencePort;
import com.nexa.api.iam.application.port.out.OrganizationRegistrationPersistencePort;
import com.nexa.api.iam.application.port.out.PasswordHashPort;
import com.nexa.api.iam.application.port.out.PasswordResetPersistencePort;
import com.nexa.api.iam.application.port.out.SecurityNotificationOutboxPort;
import com.nexa.api.iam.domain.model.passwordreset.PasswordResetExpiry;
import com.nexa.api.iam.domain.model.passwordreset.PasswordResetRequest;
import com.nexa.api.iam.domain.model.passwordreset.PasswordResetRequestId;
import com.nexa.api.iam.domain.model.passwordreset.PasswordResetTokenHash;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantmanagement.domain.model.registration.FounderIdentity;
import com.nexa.api.tenantmanagement.domain.model.registration.OrganizationRegistration;
import com.nexa.api.tenantmanagement.domain.model.registration.OrganizationRegistrationId;
import com.nexa.api.tenantmanagement.domain.model.registration.OrganizationRegistrationStatus;
import com.nexa.api.tenantmanagement.domain.model.registration.ReferencePlan;
import com.nexa.api.tenantmanagement.domain.model.registration.RegistrationStatusTokenHash;
import com.nexa.api.tenantmanagement.domain.model.registration.TermsAcceptance;
import com.nexa.api.tenantmanagement.domain.model.registration.WorkspaceSlug;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class OrganizationRegistrationService implements SubmitOrganizationRegistrationCommand,
        GetOrganizationRegistrationStatusQuery, ActivateOrganizationRegistrationCommand, RejectOrganizationRegistrationCommand {
    private static final Set<String> FOUNDER_ROLES = Set.of("TENANT_ADMIN", "COMPANY_OWNER");

    private final OrganizationRegistrationPersistencePort registrations;
    private final OrganizationActivationPersistencePort activations;
    private final MembershipRolePersistencePort roles;
    private final PasswordResetPersistencePort resets;
    private final OpaqueSecurityTokenPort tokens;
    private final PasswordHashPort hasher;
    private final SecurityNotificationOutboxPort outbox;
    private final SecurityAuditPort audit;
    private final Clock clock;
    private final Duration resetTtl;

    public OrganizationRegistrationService(OrganizationRegistrationPersistencePort registrations,
            OrganizationActivationPersistencePort activations, MembershipRolePersistencePort roles,
            PasswordResetPersistencePort resets, OpaqueSecurityTokenPort tokens, PasswordHashPort hasher,
            SecurityNotificationOutboxPort outbox, SecurityAuditPort audit, Clock clock,
            @Value("${nexa.security.reset.ttl:PT30M}") Duration resetTtl) {
        this.registrations = registrations; this.activations = activations; this.roles = roles; this.resets = resets;
        this.tokens = tokens; this.hasher = hasher; this.outbox = outbox; this.audit = audit; this.clock = clock; this.resetTtl = resetTtl;
    }

    @Override
    @Transactional
    public Registration submit(RegistrationRequest request, String correlationId, String traceId) {
        validateRequest(request);
        Instant now = clock.instant();
        String statusToken = tokens.generate();
        OrganizationRegistration registration = OrganizationRegistration.submit(new OrganizationRegistrationId(UUID.randomUUID()),
                new FounderIdentity(request.founderEmail(), request.founderDisplayName()), new WorkspaceSlug(request.workspaceSlug()),
                new TermsAcceptance(request.termsVersion(), request.termsAccepted()), ReferencePlan.valueOf(request.referencePlan()),
                new RegistrationStatusTokenHash(tokens.sha256(statusToken)));
        registrations.save(registration, request, now);
        audit.append(new SecurityAuditPort.Event("ORGANIZATION_REGISTRATION_SUBMITTED", null, null, null, null, "PUBLIC",
                valueOrUnknown(correlationId), valueOrUnknown(traceId), now, Map.of("registrationId", registration.id().value().toString(), "status", registration.status().name())));
        return new Registration(registration.id().value().toString(), registration.status().name(), now, statusToken);
    }

    @Override
    @Transactional(readOnly = true)
    public Registration get(UUID registrationId, String statusToken) {
        return registrations.findStatus(registrationId, statusToken == null ? null : tokens.sha256(statusToken));
    }

    @Override
    @Transactional
    public Activation activate(UUID registrationId, SystemOperatorContext operator, String correlationId, String traceId) {
        requireOperator(operator);
        Instant now = clock.instant();
        var snapshot = activations.findForUpdate(registrationId).orElseThrow(() -> new com.nexa.api.shared.application.error.ApiResourceNotFoundException("organization registration"));
        OrganizationRegistration aggregate = restore(snapshot);
        try { aggregate.activate(); } catch (IllegalStateException exception) { throw new IamSecurityException("REGISTRATION_NOT_PENDING"); }

        String initialPassword = tokens.generate();
        var activated = activations.createActivatedOrganization(aggregate, snapshot.displayName(), snapshot.workspaceName(), hasher.encode(initialPassword), now);
        roles.assignFounderRoles(activated.membershipId(), activated.tenantId(), activated.workspaceId(), FOUNDER_ROLES);

        String resetToken = tokens.generate();
        PasswordResetRequest reset = PasswordResetRequest.pending(new PasswordResetRequestId(UUID.randomUUID()),
                new PasswordResetTokenHash(tokens.sha256(resetToken)), now, new PasswordResetExpiry(now.plus(resetTtl)));
        resets.save(activated.founderEmail(), "PLATFORM", reset);
        outbox.enqueuePasswordReset(activated.founderEmail(), "PLATFORM", resetToken, reset.expiry().value());
        activations.markActivated(registrationId, activated.tenantId(), activated.workspaceId(), now);
        audit.append(new SecurityAuditPort.Event("ORGANIZATION_ACTIVATED", null, activated.founderUserId(), activated.tenantId(), activated.workspaceId(),
                "SYSTEM", valueOrUnknown(correlationId), valueOrUnknown(traceId), now, Map.of("registrationId", registrationId.toString())));
        return new Activation(registrationId.toString(), aggregate.status().name(), activated.tenantId(), activated.workspaceId(), activated.founderUserId(), FOUNDER_ROLES);
    }

    @Override
    @Transactional
    public Registration reject(UUID registrationId, SystemOperatorContext operator, String reason, String correlationId, String traceId) {
        requireOperator(operator);
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new IamSecurityException("REJECTION_REASON_REQUIRED");
        Instant now = clock.instant();
        var snapshot = activations.findForUpdate(registrationId).orElseThrow(() -> new com.nexa.api.shared.application.error.ApiResourceNotFoundException("organization registration"));
        OrganizationRegistration aggregate = restore(snapshot);
        try { aggregate.reject(); } catch (IllegalStateException exception) { throw new IamSecurityException("REGISTRATION_NOT_PENDING"); }
        activations.markRejected(registrationId, reason.trim(), now);
        audit.append(new SecurityAuditPort.Event("ORGANIZATION_REJECTED", null, null, null, null, "SYSTEM",
                valueOrUnknown(correlationId), valueOrUnknown(traceId), now, Map.of("registrationId", registrationId.toString())));
        return registrations.findStatus(registrationId, null);
    }

    private static OrganizationRegistration restore(OrganizationActivationPersistencePort.RegistrationSnapshot snapshot) {
        return OrganizationRegistration.restore(new OrganizationRegistrationId(snapshot.id()),
                new FounderIdentity(snapshot.founderEmail(), snapshot.founderDisplayName()), new WorkspaceSlug(snapshot.workspaceSlug()),
                new TermsAcceptance(snapshot.termsVersion(), true), ReferencePlan.valueOf(snapshot.referencePlan()),
                new RegistrationStatusTokenHash(snapshot.statusTokenHash()), OrganizationRegistrationStatus.valueOf(snapshot.status()));
    }

    private static void validateRequest(RegistrationRequest request) {
        if (request == null || blank(request.legalName()) || blank(request.displayName()) || blank(request.operationCategory())
                || blank(request.storageSiteName()) || blank(request.storageSiteAddress()) || blank(request.founderEmail())
                || blank(request.founderDisplayName()) || blank(request.workspaceName()) || blank(request.workspaceSlug())
                || !Set.of("b2bColdChainDistributor", "refrigeratedWarehouseOperator", "foodServiceSupplier", "thirdPartyColdStorage").contains(request.operationCategory())
                || !Set.of("Starter", "Standard", "Professional", "Enterprise").contains(request.referencePlan()) || !request.termsAccepted()
                || !request.workspaceSlug().matches("[A-Za-z0-9-]{3,80}") || !request.founderEmail().contains("@")) throw new IamSecurityException("REGISTRATION_INVALID");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String valueOrUnknown(String value) { return blank(value) ? "unknown" : value; }
    private static void requireOperator(SystemOperatorContext operator) {
        if (operator == null || !"system:organizations:activate".equals(operator.permission())) throw new IamSecurityException("SYSTEM_OPERATOR_REQUIRED");
    }
}
