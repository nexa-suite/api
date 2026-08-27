package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.tenantaccessgovernance.iam.application.model.SystemOperatorContext;
import java.util.UUID;

public interface RejectOrganizationRegistrationCommand {
    Registration reject(UUID registrationId, SystemOperatorContext operator, String reason, String correlationId, String traceId);
}
