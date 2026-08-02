package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.iam.application.model.SystemOperatorContext;
import java.util.UUID;

public interface ActivateOrganizationRegistrationCommand {
    Activation activate(UUID registrationId, SystemOperatorContext operator, String correlationId, String traceId);
}
