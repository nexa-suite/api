package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.IamSecurityModels.RegistrationRequest;

public interface SubmitOrganizationRegistrationCommand {
    Registration submit(RegistrationRequest request, String correlationId, String traceId);
}
