package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SystemOperatorContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.registration.OrganizationRegistration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Locked organization-registration transition intent. */
public interface OrganizationActivationPersistencePort {
    Optional<RegistrationSnapshot> findForUpdate(UUID registrationId);
    ActivatedOrganization createActivatedOrganization(OrganizationRegistration registration, OrganizationSeed organization,
            String workspaceName, String initialPasswordHash, Instant now);
    void markActivated(UUID registrationId, UUID tenantId, UUID workspaceId, UUID founderUserId, Instant now);
    void markRejected(UUID registrationId, String reason, Instant now);

    record RegistrationSnapshot(UUID id, String legalName, String displayName, String businessIdentifier,
            String operationCategory, String workspaceName, String workspaceSlug,
            String founderEmail, String founderDisplayName, String termsVersion, String statusTokenHash,
            String referencePlan, String status, Instant submittedAt, UUID tenantId, UUID workspaceId,
            UUID founderUserId) { }
    record OrganizationSeed(String legalName, String displayName, String businessIdentifier, String operationCategory) { }
    record ActivatedOrganization(UUID tenantId, UUID workspaceId, UUID founderUserId, UUID membershipId, String founderEmail) { }
}
