package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SystemOperatorContext;
import java.util.UUID;

public interface ActivateOrganizationRegistrationCommand {
    Activation activate(UUID registrationId, SystemOperatorContext operator, String correlationId, String traceId);
}
