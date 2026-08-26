package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Registration;
import java.util.UUID;

public interface GetOrganizationRegistrationStatusQuery {
    Registration get(UUID registrationId, String statusToken);
}
