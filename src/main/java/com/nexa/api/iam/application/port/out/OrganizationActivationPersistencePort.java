package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.SystemOperatorContext;
import java.util.UUID;

/** Locked organization-registration transition intent. */
public interface OrganizationActivationPersistencePort {
    Activation activate(UUID registrationId, SystemOperatorContext operator, String correlationId, String traceId);
    Registration reject(UUID registrationId, SystemOperatorContext operator, String reason, String correlationId, String traceId);
}
