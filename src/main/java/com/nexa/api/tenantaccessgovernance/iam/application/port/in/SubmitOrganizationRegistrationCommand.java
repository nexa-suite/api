package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.RegistrationRequest;

public interface SubmitOrganizationRegistrationCommand {
    Registration submit(RegistrationRequest request, String correlationId, String traceId);
}
