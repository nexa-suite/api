package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.RegistrationRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.registration.OrganizationRegistration;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

/** Public registration persistence intent. */
public interface OrganizationRegistrationPersistencePort {
    void save(OrganizationRegistration registration, RegistrationRequest request, Instant submittedAt);
    Registration findStatus(UUID registrationId, String statusTokenHash);
}
