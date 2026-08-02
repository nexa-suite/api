package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.IamSecurityModels.RegistrationRequest;
import java.util.UUID;

/** Public registration persistence intent. */
public interface OrganizationRegistrationPersistencePort {
    Registration submit(RegistrationRequest request, String correlationId, String traceId);
    Registration findStatus(UUID registrationId, String statusToken);
}
